#!/usr/bin/env python

import os
import sys
import urllib
import json
from email.Utils import COMMASPACE, formatdate
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
import smtplib
import optparse

PARTA_CONSENTED_URL = 'http://draco.mskcc.org:9890/get_12245_list_parta'
PARTC_CONSENTED_URL = 'http://draco.mskcc.org:9890/get_12245_list_partc'

PARTA_FIELD_NAME = 'PARTA_CONSENTED_12_245'
PARTC_FIELD_NAME = 'PARTC_CONSENTED_12_245'

MESSAGE_SENDER = 'cbioportal@cbioportal.org'
SMTP_SERVER = 'smtp.gmail.com'
MESSAGE_RECIPIENTS = ['cbioportal-pipelines@cbioportal.org']
CONSENT_STATUS_EMAIL_SUBJECT = 'CVR Part A & C Consent Status Updates'

MUTATION_STATUS_COLUMN = "Mutation_Status"
SAMPLE_ID_COLUMN = "Tumor_Sample_Barcode"
GERMLINE_MUTATION_STATUS = "GERMLINE"

ERROR_FILE = sys.stderr

CVR_CONSENT_STATUS_ENDPOINTS = {
    PARTA_FIELD_NAME : PARTA_CONSENTED_URL,
    PARTC_FIELD_NAME : PARTC_CONSENTED_URL
}

def fetch_expected_consent_status_values():
    '''
        Fetches expected consent status values for 12-245
        Part A and C from CVR web service.
    '''
    expected_consent_status_values = {}
    for field,url in CVR_CONSENT_STATUS_ENDPOINTS.items():
        response = urllib.urlopen(url)
        data = json.loads(response.read())

        consent_values = {}
        if 'status' not in data or data['status'] != "SUCCESS":
            print >> ERROR_FILE, "WARNING: Could not retrieve %s consent values from germline server at %s" % (field, url)
        else:
            for pt,status in data['cases'].items():
                if status:
                    consent_values[pt] = 'YES'
                else:
                    consent_values[pt] = 'NO'
        expected_consent_status_values[field] = consent_values
    return expected_consent_status_values

def cvr_consent_status_fetcher_main(cvr_clinical_file, cvr_mutation_file, expected_consent_status_values, gmail_username, gmail_password):
    '''
        Checks the current consent status for
        Part A & C against the expected consent status values
        from CVR web service.

        A report of which samples need to be requeued or removed
        from data set is emailed to recipients.

        Samples are added to the requeue list if their expected consent
        status is 'YES' and their current status is 'NO'.

        Samples are added to the removal list if their expected consent
        status is 'NO' and their current status is 'YES'.
    '''
    samples_to_requeue = {}
    samples_to_remove = {}

    # Iterates thru each record in the data file.
    # Remove samples if their Part A or Part C consent status has changed.
    with open(cvr_clinical_file, 'rU') as data_file:
        lines = data_file.readlines()
        header = map(str.strip, lines.pop().split('\t'))
        records = [
            dict(zip(header, map(str.strip, line.split('\t')))) for line in lines
        ]
        for field in CVR_CONSENT_STATUS_ENDPOINTS.keys():
            consents_changed = {
                # Sample ID => current consent status
                record['SAMPLE_ID']: record[field]
                for record in records
                if record[field] != expected_consent_status_values[field].get(record['PATIENT_ID'], 'NO')
            }
            pct_consents_changed = 100*(len(consents_changed) / len(records))
            # If the % of records changed is above some threshold, assume something is up
            # with the server and don't take any action. (20 is somewhat arbitrary)
            cutoff = 20
            if pct_consents_changed > cutoff:
                print >> ERROR_FILE, "WARNING: More than %s%% of samples have had their %s consent status changed. No action will be taken." % (cutoff, field)
                continue

            # Remove all of the records with their consent status changed for this field
            for sample_id, current_consent in consents_changed.items():
                # if patient has granted consent then add samples to requeue list
                # otherwise if patient has since revoked consent then add sample to
                # set of samples to remove from data set
                if current_consent == 'NO': # expected_consent == 'YES'
                    requeue_list = samples_to_requeue.get(field, set())
                    requeue_list.add(sample_id)
                    samples_to_requeue[field] = requeue_list
                elif current_consent == 'YES': # expected_consent == 'NO'
                    remove_list = samples_to_remove.get(field, set())
                    remove_list.add(sample_id)
                    samples_to_remove[field] = remove_list

    if samples_to_remove.get(PARTC_FIELD_NAME, set()):
        remove_germline_revoked_samples(cvr_mutation_file, samples_to_remove.get(PARTC_FIELD_NAME))
    if samples_to_requeue != {} or samples_to_remove != {}:
        email_consent_status_report(samples_to_requeue, samples_to_remove, gmail_username, gmail_password)

def remove_germline_revoked_samples(cvr_mutation_file, revoked_germline_samples):
    '''
        Removes germline mutation records from MAF for samples where
        Part C Consent Status has changed from Yes to No.
    '''

    tmpfile_name = cvr_mutation_file + ".tmp"
    tmpfile = open(tmpfile_name, "w")
    with open(cvr_mutation_file, 'rU') as data_file:
        header = []
        num_germline_records = 0
        num_removed_records = 0

        lines = data_file.readlines()
        for line in lines:
            if line.startswith("#"):
                tmpfile.write(line)
                continue
            if not header:
                header = map(str.strip, line.split('\t'))
                tmpfile.write(line)
                continue
            record = dict(zip(header, map(str.strip, line.split('\t'))))
            if record[MUTATION_STATUS_COLUMN] == GERMLINE_MUTATION_STATUS:
                num_germline_records += 1
                if record[SAMPLE_ID_COLUMN] in revoked_germline_samples:
                    num_removed_records += 1
                    continue
            tmpfile.write(line)
    tmpfile.close()

    pct_removed = 100*(num_removed_records / num_germline_records)
    if pct_removed >= 20:
        print >> ERROR_FILE, "WARNING: %s%% of germline records had their Part C consent status changed. No action will be taken-- please double-check the response of the upstream server."
        return

    os.rename(tmpfile_name, cvr_mutation_file)

def generate_attachment(message, attachment_name, samples):
    '''
        Generates email attachment.
    '''
    report = MIMEText('\n'.join(list(samples)))
    report.add_header('Content-Disposition', 'attachment', filename = attachment_name)
    message.attach(report)

def email_consent_status_report(samples_to_requeue, samples_to_remove, gmail_username, gmail_password):
    '''
        Constructs and sends email reporting consent status updates.
    '''
    summary = 'Consent status report summary:\n'
    message = MIMEMultipart()
    if samples_to_requeue != {}:
        summary += '\n\nCONSENT GRANTED:'
        for field,samples in samples_to_requeue.items():
            summary += '\n\t%s:\t%s samples' % (field, len(samples))
            filename = field.lower() + '_consent_granted_report.txt'
            generate_attachment(message, filename, samples)

    if samples_to_remove != {}:
        summary += '\n\nCONSENT REVOKED:'
        for field,samples in samples_to_remove.items():
            summary += '\n\t%s:\t%s samples' % (field, len(samples))
            filename = field.lower() + '_consent_revoked_report.txt'
            generate_attachment(message, filename, samples)

    body = MIMEText(summary, 'plain')
    message.attach(body)

    message['Subject'] = CONSENT_STATUS_EMAIL_SUBJECT
    message['From'] = MESSAGE_SENDER
    message['To'] = COMMASPACE.join(MESSAGE_RECIPIENTS)
    message['Date'] = formatdate(localtime=True)

    s = smtplib.SMTP_SSL(SMTP_SERVER, 465)
    s.login(gmail_username, gmail_password)
    s.sendmail(MESSAGE_SENDER, MESSAGE_RECIPIENTS, message.as_string())
    s.quit()

def main():
    parser = optparse.OptionParser()
    parser.add_option('-c', '--clinical-file', action = 'store', dest = 'clinfile', help = 'CVR clinical file')
    parser.add_option('-m', '--mutation-file', action = 'store', dest = 'maf', help = 'CVR MAF')
    parser.add_option('-u', '--gmail-username', action = 'store', dest = 'gmail_username', help = 'Gmail username [required]')
    parser.add_option('-p', '--gmail-password', action = 'store', dest = 'gmail_password', help = 'Gmail SMTP password [required]')

    (options, args) = parser.parse_args()

    cvr_clinical_file = options.clinfile
    cvr_mutation_file = options.maf
    gmail_username = options.gmail_username
    gmail_password = options.gmail_password
    if not cvr_clinical_file or not os.path.exists(cvr_clinical_file):
        print >> ERROR_FILE, "Invalid CVR clinical file: %s, exiting..." % (cvr_clinical_file)
        sys.exit(2)
    if not cvr_mutation_file or not os.path.exists(cvr_mutation_file):
        print >> ERROR_FILE, "Invalid CVR mutation file: %s, exiting..." % (cvr_mutation_file)
        sys.exit(2)
    if not gmail_username:
        print >> ERROR_FILE, "Required option --gmail-username/-u missing, exiting..." % (cvr_mutation_file)
        sys.exit(2)
    if not gmail_password:
        print >> ERROR_FILE, "Required option --gmail-password/-p missing, exiting..." % (cvr_mutation_file)
        sys.exit(2)

    expected_consent_status_values = fetch_expected_consent_status_values()
    cvr_consent_status_fetcher_main(cvr_clinical_file, cvr_mutation_file, expected_consent_status_values, gmail_username, gmail_password)

if __name__ == '__main__':
    main()

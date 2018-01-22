/*
 * Copyright (c) 2016 - 2017 Memorial Sloan-Kettering Cancer Center.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY, WITHOUT EVEN THE IMPLIED WARRANTY OF MERCHANTABILITY OR FITNESS
 * FOR A PARTICULAR PURPOSE. The software and documentation provided hereunder
 * is on an "as is" basis, and Memorial Sloan-Kettering Cancer Center has no
 * obligations to provide maintenance, support, updates, enhancements or
 * modifications. In no event shall Memorial Sloan-Kettering Cancer Center be
 * liable to any party for direct, indirect, special, incidental or
 * consequential damages, including lost profits, arising out of the use of this
 * software and its documentation, even if Memorial Sloan-Kettering Cancer
 * Center has been advised of the possibility of such damage.
 */

/*
 * This file is part of cBioPortal CMO-Pipelines.
 *
 * cBioPortal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.cbioportal.cmo.pipelines.cvr.clinical;

import java.io.*;
import java.util.*;
import org.apache.commons.lang.StringUtils;
import org.cbioportal.cmo.pipelines.cvr.CVRUtilities;
import org.cbioportal.cmo.pipelines.cvr.model.CVRClinicalRecord;
import org.springframework.batch.item.*;
import org.springframework.batch.item.file.*;
import org.springframework.batch.item.file.transform.PassThroughLineAggregator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.cbioportal.cmo.pipelines.cvr.model.CompositeClinicalRecord;
import org.springframework.beans.factory.annotation.Autowired;


/**
 *
 * @author heinsz
 */
public class CVRSeqDateClinicalDataWriter implements ItemStreamWriter<CompositeClinicalRecord> {

    @Value("#{jobParameters[stagingDirectory]}")
    private String stagingDirectory;

    @Value("#{jobParameters[studyId]}")
    private String studyId;

    @Autowired
    public CVRUtilities cvrUtilities;

    private FlatFileItemWriter<String> flatFileItemWriter = new FlatFileItemWriter<>();

    // Set up the writer and print the json from CVR to a file
    @Override
    public void open(ExecutionContext ec) throws ItemStreamException {
        if (studyId.equals("mskimpact")) {
            File stagingFile = new File(stagingDirectory, cvrUtilities.SEQ_DATE_CLINICAL_FILE);
            PassThroughLineAggregator aggr = new PassThroughLineAggregator();
            flatFileItemWriter.setLineAggregator(aggr);
            flatFileItemWriter.setHeaderCallback(new FlatFileHeaderCallback() {
                @Override
                public void writeHeader(Writer writer) throws IOException {
                    writer.write(StringUtils.join(CVRClinicalRecord.getSeqDateFieldNames(), "\t"));
                }
            });
            flatFileItemWriter.setResource(new FileSystemResource(stagingFile));
            flatFileItemWriter.open(ec);
        }
    }

    @Override
    public void update(ExecutionContext ec) throws ItemStreamException {
    }

    @Override
    public void close() throws ItemStreamException {
        if (studyId.equals("mskimpact")) {
            flatFileItemWriter.close();
        }
    }

    @Override
    public void write(List<? extends CompositeClinicalRecord> items) throws Exception {
        if (studyId.equals("mskimpact")) {
            List<String> writeList = new ArrayList<>();
            for (CompositeClinicalRecord item : items) {
                if (item.getSeqDateRecord()!= null) {
                    writeList.add(item.getSeqDateRecord());
                }
            }
            flatFileItemWriter.write(writeList);
        }
    }
}

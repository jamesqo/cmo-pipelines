/*
 * Copyright (c) 2016 Memorial Sloan-Kettering Cancer Center.
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
package org.cbioportal.cmo.clinical.data.pipeline;

import java.util.*;
import org.apache.log4j.Logger;
import org.cbioportal.cmo.clinical.data.source.ClinicalDataSource;
import org.springframework.batch.core.*;
import org.springframework.batch.item.*;
import org.springframework.batch.core.configuration.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.batch.item.support.CompositeItemWriter;
import org.springframework.batch.core.job.flow.*;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;

/**
 *
 * @author heinsz
 */

@Configuration
@EnableBatchProcessing
@ComponentScan(basePackages="org.cbioportal.clinical.data.source.internal")
public class BatchConfiguration {
    public static final String CLINICAL_DATA_JOB = "clinicalDataJob";   
    
    private final Logger log = Logger.getLogger(BatchConfiguration.class);    
    
    @Value("${chunk}")
    private String chunk;      
	
    @Autowired
    public ClinicalDataSource clinicalDataSource;    

    @Autowired
    public JobBuilderFactory jobBuilderFactory;
    
    @Autowired
    public StepBuilderFactory stepBuilderFactory;
    
    @Bean
    public JobExecutionDecider timelineDecider() {
        return new JobExecutionDecider() {
            @Override
            public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
                if ((boolean)stepExecution.getExecutionContext().get("timelineExists")) {
                    return new FlowExecutionStatus("YES");
                }
                log.info("No project specified to generate timeline data for - skipping.");
                return new FlowExecutionStatus("NO");
            }            
        };
    }    
    
   @Bean
    public Job clinicalDataJob() {
        return jobBuilderFactory.get(CLINICAL_DATA_JOB)
                .start(clinicalDataStep())
                .next(timelineDecider())
                .on("YES")
                .to(timelineDataStep())
                .from(timelineDecider())
                .on("NO")
                .end()
                .end()
                .build();
    }
    
    @Bean
    public Step clinicalDataStep() {
        return stepBuilderFactory.get("clinicalDataStep")
                .<Map<String, String>, ClinicalDataComposite> chunk(Integer.parseInt(chunk))
                .reader(clinicalDataReader())
                .processor(clinicalDataprocessor())
                .writer(clinicalDatawriter())
                .build();
    }
    
    @Bean
    public Step timelineDataStep() {
        return stepBuilderFactory.get("timelineDataStep")
                .<Map<String, String>, String> chunk(10)
                .reader(timelineReader())
                .processor(timelineProcessor())
                .writer(timelineWriter())
                .build();
    }    
    
    @Bean
    @StepScope
    public ItemStreamReader<Map<String, String>> clinicalDataReader() {
        return new ClinicalDataReader();        
    }
    
    // Using a composite processor pattern to avoid having to hit redcap api more than needed.
    // Sample processor/writer leads into the patient processor writer - the composite result object is passed along 
    // which contains the data necessary for the next processor/writer and the result of the processors.
    // The writers pull out the data they need from the composite result.
    
    @Bean
    @StepScope
    public ItemProcessor clinicalDataprocessor() {
        CompositeItemProcessor processor = new CompositeItemProcessor();
        List<ItemProcessor> delegates = new ArrayList<>();
        delegates.add(sampleProcessor());
        delegates.add(patientProcessor());
        processor.setDelegates(delegates);
        return processor;
    }
    
    @Bean
    @StepScope
    public CompositeItemWriter<ClinicalDataComposite> clinicalDatawriter() {
        CompositeItemWriter writer = new CompositeItemWriter();
        List<ItemWriter> delegates = new ArrayList<>();
        delegates.add(sampleWriter());
        delegates.add(patientWriter());
        writer.setDelegates(delegates);
        return writer;
    }
    
    @Bean
    @StepScope
    public ClinicalSampleDataProcessor sampleProcessor() {
        ClinicalSampleDataProcessor sampleProcessor = new ClinicalSampleDataProcessor();
        return sampleProcessor;
    }
    
    @Bean
    @StepScope
    public ClinicalPatientDataProcessor patientProcessor() {
        ClinicalPatientDataProcessor patientProcessor = new ClinicalPatientDataProcessor();
        return patientProcessor;
    }
    
    @Bean
    @StepScope
    public ClinicalSampleDataWriter sampleWriter() {
        ClinicalSampleDataWriter sampleWriter = new ClinicalSampleDataWriter();
        return sampleWriter;
    }
    
    @Bean
    @StepScope
    public ClinicalPatientDataWriter patientWriter() {
        ClinicalPatientDataWriter patientWriter = new ClinicalPatientDataWriter();
        return patientWriter;
    }
    
    // timeline processor / writers
    @Bean
    @StepScope
    public ItemStreamReader<Map<String, String>> timelineReader() {
        return new TimelineReader();        
    }
    
    @Bean
    @StepScope
    public TimelineProcessor timelineProcessor() {
        return new TimelineProcessor();
    }
    
    @Bean
    @StepScope
    public TimelineWriter timelineWriter() {
        return new TimelineWriter();
    }    
}

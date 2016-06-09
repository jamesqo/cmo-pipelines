/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.cbioportal.cmo.pipelines.darwin;

import org.cbioportal.cmo.pipelines.darwin.model.DarwinClinicalBrainSpine;

import static com.querydsl.core.alias.Alias.$;
import static com.querydsl.core.alias.Alias.alias;
import com.querydsl.core.types.Projections;
import com.querydsl.sql.SQLQueryFactory;

import org.springframework.batch.item.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
/**
 *
 * @author jake
 */
public class DarwinClinicalBrainSpineReader implements ItemStreamReader<DarwinClinicalBrainSpine>{
    @Value("${darwin.clinical_view}")
    private String clinicalBrainSpineView;
    
    @Autowired
    SQLQueryFactory darwinQueryFactory;
    
    private List<DarwinClinicalBrainSpine> clinicalBrainSpineResults;
    
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException{
        this.clinicalBrainSpineResults = getClinicalBrainSpineResults();
    }
    
    @Transactional
    private List<DarwinClinicalBrainSpine> getClinicalBrainSpineResults(){
        System.out.println("Start of Clinical Brain Spine View Import...");
        DarwinClinicalBrainSpine qCBSR = alias(DarwinClinicalBrainSpine.class, clinicalBrainSpineView);
        List<DarwinClinicalBrainSpine> clinicalBrainSpineResults = darwinQueryFactory.select(Projections.constructor(DarwinClinicalBrainSpine.class, 
                $(qCBSR.getDMT_PATIENT_ID_BRAINSPINECLIN()),
                $(qCBSR.getDMP_PATIENT_ID_BRAINSPINECLIN()),
                $(qCBSR.getDMP_SAMPLE_ID_BRAINSPINECLIN()),
                $(qCBSR.getAGE()),
                $(qCBSR.getSEX()),
                $(qCBSR.getOS_STATUS()),
                $(qCBSR.getOS_MONTHS()),
                $(qCBSR.getDFS_STATUS()),
                $(qCBSR.getDFS_MONTHS()),
                $(qCBSR.getHISTOLOGY()),
                $(qCBSR.getWHO_GRADE()),
                $(qCBSR.getMGMT_STATUS())))
                .where($(qCBSR.getDMT_PATIENT_ID_BRAINSPINECLIN().equals("")).isFalse())
                .from($(qCBSR))
                .fetch();
        
        System.out.println("Imported " + clinicalBrainSpineResults.size() + " records from Clinical Brain Spine View.");
        return clinicalBrainSpineResults;
    }
    
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException{}
    
    @Override
    public void close() throws ItemStreamException{}
    
    @Override
    public DarwinClinicalBrainSpine read() throws Exception{
        if(!clinicalBrainSpineResults.isEmpty()){
            return clinicalBrainSpineResults.remove(0);
        }
        return null;
    }
    
}

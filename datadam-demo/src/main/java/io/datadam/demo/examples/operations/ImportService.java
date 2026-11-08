package io.datadam.demo.examples.operations;
import java.util.*;
import io.datadam.demo.examples.catalog.*;
import org.springframework.stereotype.Service;
import org.springframework.dao.*;
@Service
public class ImportService {
    private final ImportJobRepository jobs;private final ProductRepository products;
    public ImportService(ImportJobRepository jobs,ProductRepository products){this.jobs=jobs;this.products=products;}
    public ImportJob create(){return jobs.save(new ImportJob(null,"PENDING",0,0,List.of(),null));}
    /** Two-step API: callers know the job ID before processing and can poll it from another connection. */
    public ImportJob run(String id,List<Product> rows){
        if(rows==null||rows.isEmpty()||rows.size()>100)throw new IllegalArgumentException("Supply between 1 and 100 products");
        if(rows.stream().anyMatch(row->row==null||row.id()!=null))throw new IllegalArgumentException("Imports create new products only");
        var job=jobs.findById(id).orElseThrow(()->new NoSuchElementException("Import not found"));
        if(!"PENDING".equals(job.status()))throw new IllegalArgumentException("Only a PENDING job can run; reconcile uncertain jobs explicitly");
        job=jobs.save(new ImportJob(id,"RUNNING",0,0,List.of(),job.version()));
        var ids=new ArrayList<String>();int rejected=0;
        for(var row:rows){
            try { ids.add(products.save(row).id()); }
            catch(DuplicateKeyException|IllegalArgumentException e){rejected++;}
            catch(DataAccessResourceFailureException e){
                // No retry: the product may have been written even though its ID was not acknowledged.
                try{jobs.save(new ImportJob(id,"UNCERTAIN",ids.size(),rejected,List.copyOf(ids),job.version()));}
                catch(RuntimeException statusFailure){e.addSuppressed(statusFailure);}throw e;
            }
            job=jobs.save(new ImportJob(id,"RUNNING",ids.size(),rejected,List.copyOf(ids),job.version()));
        }
        return jobs.save(new ImportJob(id,rejected==0?"COMPLETED":"PARTIAL",ids.size(),rejected,List.copyOf(ids),job.version()));
    }
}

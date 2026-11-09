package com.anlohse.datadam.demo.performance;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import com.anlohse.datadam.spring.*;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.DatadamStorage;
import com.anlohse.datadam.spring.annotation.IndexField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anlohse.datadam.spring.config.DatadamAutoConfiguration;
import com.anlohse.datadam.spring.repository.SimpleDatadamRepository;
import com.anlohse.datadam.demo.security.DisposableDatadam;
import com.anlohse.datadam.demo.support.TestImages;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import redis.clients.jedis.JedisPool;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in workload; its name excludes it from the ordinary Failsafe *IT suite. No latency thresholds. */
class NativePerformanceBenchmark {
    @TempDir Path directory;
    static volatile Object sink;
    final ObjectMapper mapper = new ObjectMapper();
    final List<String> csv = new ArrayList<>(List.of("documents,format,operation,validate_connection,matches,offset,limit,warmups,samples,commands_per_operation,median_us,p95_us,mean_us,serial_operations_per_second"));
    final int samples = Integer.getInteger("datadam.benchmark.samples", 50), warmups = Integer.getInteger("datadam.benchmark.warmups", 10);
    @Test @Timeout(300)
    void measureNativeWorkloads() throws Exception {
        assertThat(samples).isBetween(10, 1000); assertThat(warmups).isBetween(0, 1000);
        int[] volumes = Arrays.stream(System.getProperty("datadam.benchmark.documents", "1000,10000").split(",")).mapToInt(Integer::parseInt).toArray();
        var environment = new LinkedHashMap<String,Object>();
        environment.put("timestampUtc", Instant.now().toString()); environment.put("java", System.getProperty("java.runtime.version"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        environment.put("clientProcessors", Runtime.getRuntime().availableProcessors()); environment.put("clientCpu", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "not reported"));
        environment.put("datadamImage", TestImages.datadam()); environment.put("datadamRevision", TestImages.revision());
        environment.put("docker", DisposableDatadam.docker("version", "--format", "{{.Server.Version}}").trim());
        environment.put("dockerResources", DisposableDatadam.docker("info", "--format", "{{.NCPU}} CPUs; {{.MemTotal}} bytes; {{.Architecture}}").trim());
        environment.put("serverLimits", "2 CPUs, 1 GiB RAM, read-threads=2"); environment.put("concurrency", 1);
        environment.put("payloadCharacters", 256); environment.put("groups", 10); environment.put("pageSize", 20);
        environment.put("samples", samples); environment.put("warmups", warmups);
        var seeds = new LinkedHashMap<String,Long>();
        for (int volume : volumes) {
            assertThat(volume).isBetween(1000, 100000);
            try (var server = new DisposableDatadam(directory.resolve("data-" + volume), "protected-mode no\n", false, List.of("--cpus","2","--memory","1g")); var pool = server.pool()) {
                var commands = new DatadamCommands(pool); var indexes = new DatadamIndexOperations(commands);
                indexes.ensureIndexes(JsonCustomer.class, Duration.ofSeconds(30)); indexes.ensureIndexes(HashCustomer.class, Duration.ofSeconds(30));
                var template = new DatadamTemplate(commands, mapper); long seedStart = System.nanoTime();
                for (int i = 0; i < volume; i++) {
                    String id = String.format(Locale.ROOT,"%06d",i), city="g"+(i%10), email=id+"@bench.test", payload="x".repeat(256);
                    template.save(new JsonCustomer(id,city,i%100,email,payload),new EntityModel<>(JsonCustomer.class));
                    template.save(new HashCustomer(id,city,i%100,email,payload),new EntityModel<>(HashCustomer.class));
                }
                seeds.put(Integer.toString(volume), (System.nanoTime()-seedStart)/1_000_000);
                for (boolean validate : List.of(false,true)) {
                    var p=server.properties(); p.setValidateConnection(validate); p.setReadTimeout(Duration.ofSeconds(5));
                    try (var measuredPool=new DatadamAutoConfiguration().datadamPool(p)) {
                        var measured=new Recording(measuredPool); var client=new DatadamTemplate(measured,mapper);
                        measure(volume,"JSON",validate,client,measured,new EntityModel<>(JsonCustomer.class),"bench:json",new JsonCustomer("000000","g0",40,"000000@bench.test","u".repeat(256)));
                        measure(volume,"HASH",validate,client,measured,new EntityModel<>(HashCustomer.class),"bench:hash",new HashCustomer("000000","g0",40,"000000@bench.test","u".repeat(256)));
                    }
                }
            }
        }
        environment.put("seedMillisForBothFormats",seeds);
        Path output=Path.of("target","performance");Files.createDirectories(output);
        Files.write(output.resolve("results.csv"),csv);mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("environment.json").toFile(),environment);
    }
    <T> void measure(int volume,String format,boolean validate,DatadamTemplate client,Recording commands,EntityModel<T> model,String index,T update) {
        int matches=volume/10, deep=matches-20; var query= CompoundQuery.all().where("city","=","g0").where("age",">=",0);
        var ft=new SearchQuery("@city:{$c} @age:[0 +inf]",Map.of("c","g0"),Sort.by("age"));
        assertThat(client.count(index+":idx",query,model)).isEqualTo(matches);
        run(volume,format,"idx.content.first",validate,matches,0,20,samples,commands,()->client.queryContent(index+":idx",query,0,20,model));
        run(volume,format,"idx.page.first",validate,matches,0,20,samples,commands,()->client.query(index+":idx",query,PageRequest.of(0,20),model));
        run(volume,format,"idx.page.deep",validate,matches,deep,20,samples,commands,()->client.query(index+":idx",query,PageRequest.of(deep/20,20),model));
        run(volume,format,"idx.count",validate,matches,0,0,samples,commands,()->client.count(index+":idx",query,model));
        run(volume,format,"idx.unique.lookup",validate,1,0,1,samples,commands,()->client.lookup(index+":email",model,"000000@bench.test"));
        run(volume,format,"idx.projection.page",validate,matches,0,20,samples,commands,()->client.projectQuery(index+":idx",query,PageRequest.of(0,20),model,ProjectionSpec.of(Summary.class)));
        run(volume,format,"idx.list.unbounded",validate,matches,0,-1,10,commands,()->client.queryContent(index+":idx",query,0,-1,model));
        run(volume,format,"ft.page.first",validate,matches,0,20,samples,commands,()->client.search(index+":ft",ft,PageRequest.of(0,20),model));
        run(volume,format,"ft.page.deep",validate,matches,deep,20,samples,commands,()->client.search(index+":ft",ft,PageRequest.of(deep/20,20),model));
        run(volume,format,"ft.projection.page",validate,matches,0,20,samples,commands,()->client.projectSearch(index+":ft",ft,PageRequest.of(0,20),model,ProjectionSpec.of(Summary.class)));
        run(volume,format,"update.full.save",validate,1,0,1,samples,commands,()->client.save(update,model));
        run(volume,format,"update.native.increment",validate,1,0,1,samples,commands,()->commands.execute(format.equals("JSON")?"JSON.NUMINCRBY":"HINCRBY",model.key("000000"),format.equals("JSON")?"$.age":"age",1));
        var repository=new SimpleDatadamRepository<T,String>(client,model.type());
        assertThat(repository.count()).isEqualTo(volume);
        run(volume,format,"global.count.scan",validate,volume,0,0,5,commands,repository::count);
    }
    void run(int volume,String format,String operation,boolean validate,int matches,int offset,int limit,int iterations,Recording commands,Supplier<?> task) {
        for(int i=0;i<warmups;i++)sink=task.get();long before=commands.count;long[] times=new long[iterations];
        for(int i=0;i<iterations;i++){long start=System.nanoTime();sink=task.get();times[i]=System.nanoTime()-start;}
        long total=Arrays.stream(times).sum();Arrays.sort(times);double mean=(double)total/iterations;
        csv.add(String.format(Locale.ROOT,"%d,%s,%s,%s,%d,%d,%d,%d,%d,%.2f,%.3f,%.3f,%.3f,%.2f",volume,format,operation,validate,matches,offset,limit,warmups,iterations,
            (double)(commands.count-before)/iterations,times[(iterations-1)/2]/1000d,times[(int)Math.ceil(iterations*.95)-1]/1000d,mean/1000d,1e9/mean));
    }
    static class Recording extends DatadamCommands { long count;Recording(JedisPool pool){super(pool);}@Override public Object execute(String name,Object...args){count++;return super.execute(name,args);} }
    public record Summary(String city,Integer age){}
    @DatadamDocument(prefix="bench:json:")
    @DatadamIndex(name="bench:json:idx",fields={@IndexField(path="$.city",alias="city"),@IndexField(path="$.age",alias="age",type=IndexField.Type.NUMERIC)})
    @DatadamIndex(name="bench:json:email",unique=true,fields=@IndexField(path="$.email",alias="email"))
    @DatadamIndex(name="bench:json:ft",kind=DatadamIndex.Kind.SEARCH,fields={@IndexField(path="$.city",alias="city",caseSensitive=true),@IndexField(path="$.age",alias="age",type=IndexField.Type.NUMERIC)})
    public record JsonCustomer(@Id String id,String city,int age,String email,String payload){}
    @DatadamDocument(prefix="bench:hash:",storage= DatadamStorage.HASH)
    @DatadamIndex(name="bench:hash:idx",fields={@IndexField(path="city",alias="city"),@IndexField(path="age",alias="age",type=IndexField.Type.NUMERIC)})
    @DatadamIndex(name="bench:hash:email",unique=true,fields=@IndexField(path="email",alias="email"))
    @DatadamIndex(name="bench:hash:ft",kind=DatadamIndex.Kind.SEARCH,fields={@IndexField(path="city",alias="city",caseSensitive=true),@IndexField(path="age",alias="age",type=IndexField.Type.NUMERIC)})
    public record HashCustomer(@Id String id,String city,int age,String email,String payload){}
}

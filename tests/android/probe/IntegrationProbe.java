package com.simon.doubaolauncher;
import android.app.*;
import android.os.Bundle;
import java.io.*;
public class IntegrationProbe extends Instrumentation {
    private String operation;
    @Override public void onCreate(Bundle args){super.onCreate(args);operation=args.getString("op","seed");start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            if("seed".equals(operation)){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream input=getContext().getAssets().open("doubao-call-rules.json")){
                    byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))>=0)bytes.write(buffer,0,count);
                }
                DoubaoRuleParser parser=new DoubaoRuleParser();
                DoubaoRule rule=parser.parse(bytes.toString("UTF-8"));
                RuleCache cache=new RuleCache(getTargetContext(),parser);
                cache.saveIfNewer(rule);
                if(cache.load()==null)throw new AssertionError("Real rule cache unavailable");
                result.putString("stream","Seeded actual repository rule through production parser/cache\n");
            } else throw new IllegalArgumentException("Unknown test operation");
            finish(-1,result);
        }catch(Throwable error){result.putString("stream",error.toString());finish(0,result);}
    }
}

package org.classicbudget.patch;
import java.io.*;import org.json.*;
/** Explicit, private one-shot fault injection for synthetic-budget emulator tests. */
public final class WriteFault {
    public static void awaitRegistration(String path)throws IOException {
        File directory=DavProvider.context().getFilesDir(),gate=new File(directory,"registration-gate.json");if(!gate.exists())return;
        try {
            JSONObject config=new JSONObject(WriteJournal.read(gate));if(!config.optBoolean("enabled"))return;
            if(!WritePolicy.devicePath(path))throw new IOException("REGISTRATION_GATE_INVALID_PATH");
            JSONObject arrived=new JSONObject();arrived.put("path",path);
            WriteJournal.atomic(new File(directory,"registration-arrived.json"),arrived.toString());
            try(FileOutputStream log=new FileOutputStream(new File(directory,"registration-attempts.jsonl"),true)){log.write((arrived.toString()+"\n").getBytes(WritePolicy.UTF8));log.getFD().sync();}
            long timeout=Math.max(50,Math.min(60000,config.optLong("timeoutMs",45000))),until=android.os.SystemClock.elapsedRealtime()+timeout;
            while(true){
                if(new JSONObject(WriteJournal.read(gate)).optBoolean("open"))return;
                if(android.os.SystemClock.elapsedRealtime()>=until)throw new IOException("REGISTRATION_GATE_TIMEOUT");
                try{Thread.sleep(25);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("REGISTRATION_GATE_INTERRUPTED",error);}
            }
        }catch(JSONException error){throw new IOException("INVALID_REGISTRATION_GATE",error);}
    }
    public static void hit(String point)throws IOException {
        File file=new File(DavProvider.context().getFilesDir(),"write-fault.json");if(!file.exists())return;
        try {
            JSONObject fault=new JSONObject(WriteJournal.read(file));
            if(point.equals(fault.optString("point"))&&!fault.optBoolean("fired")){
                fault.put("fired",true);WriteJournal.atomic(file,fault.toString());throw new IOException("INJECTED_WRITE_TEST_FAULT_"+point);
            }
        }catch(JSONException error){throw new IOException("INVALID_FAULT_FIXTURE",error);}
    }
}

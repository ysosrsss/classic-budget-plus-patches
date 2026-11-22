package org.classicbudget.patch;

import java.io.*;
import org.json.JSONObject;

/** One storage backend for the journaled writer (WebDAV now, SAF folder next).
 * Every implementation publishes a journal entry through its own staging file
 * and confirms the final bytes; the journal and recovery stay backend-neutral. */
interface PublishTransport {
    /** Staged bytes differ from the pinned payload: the journal rotates the staging name. */
    final class StagingMismatch extends IOException {
        final String observedHash;
        StagingMismatch(byte[] bytes)throws IOException{super("STAGING_BYTES_CHANGED");observedHash=bytes==null?"missing":WritePolicy.sha(bytes);}
    }
    /** Bytes at a logical path, or null when it does not exist. */
    byte[] optional(String path)throws IOException;
    /** Ensures a logical directory exists (only paths the policy allows). */
    void directory(String path)throws IOException;
    /** True when the final path holds exactly the entry's payload; false when an immutable target is taken. */
    boolean publish(JSONObject entry,boolean replace)throws IOException;
    /** Removes the entry's own staging file after verifying its hash. */
    void removeStaging(JSONObject entry)throws IOException;
    /** A fresh, never used staging path for the given logical target. */
    String stagingFor(String target);
}

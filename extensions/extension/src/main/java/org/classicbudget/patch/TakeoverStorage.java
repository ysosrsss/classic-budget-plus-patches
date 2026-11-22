package org.classicbudget.patch;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** Dedicated write path for a confirmed transfer of a single device registration. */
interface TakeoverStorage {
    String takeoverProfile(File device) throws Exception;
    long registrationModified(File device) throws IOException;
    byte[] registrationBytes(File device) throws IOException;
    void replaceRegistration(File device, JSONObject transfer) throws Exception;

    final class Changed extends IOException {
        Changed() { super("TAKEOVER_REGISTRATION_CHANGED"); }
    }
}

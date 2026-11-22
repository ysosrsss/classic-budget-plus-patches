package com.youneedabudget.ynab.core.cloud;

import com.youneedabudget.ynab.core.backend.i;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** YNAB Classic 3.4.1_classic: cloud file accessor (same signatures, obfuscated names). */
public interface z {
    File a(File file, String text) throws IOException;
    String a(File file) throws IOException;
    InputStream b(File file) throws IOException;
    void b(File file, String text) throws IOException;
    i c(File file) throws IOException;
    List<File> c(File folder, String suffix) throws IOException;
    List<i> d(File folder, String suffix) throws IOException;
    void d(File file) throws IOException;
}

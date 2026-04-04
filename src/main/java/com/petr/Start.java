package com.petr;

import com.petr.db.migration.DatabaseMigration;

import java.io.IOException;

public class Start {
    public static void main(String[] args) throws IOException, InterruptedException {
        String environment = System.getProperty("app.env");
        DatabaseMigration.startMigration(environment);
    }
}

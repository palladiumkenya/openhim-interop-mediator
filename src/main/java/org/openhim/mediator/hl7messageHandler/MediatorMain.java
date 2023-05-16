/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.hl7messageHandler;

import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import org.apache.commons.io.IOUtils;
import org.openhim.mediator.engine.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public class MediatorMain {

    private static RoutingTable buildRoutingTable() throws RoutingTable.RouteAlreadyMappedException {
        RoutingTable routingTable = new RoutingTable();
        routingTable.addRegexRoute("/MessageProxyHandler", HL7MessageProxyHandler.class);
        return routingTable;
    }

    private static MediatorConfig loadConfig(String configPath, String regConfigPath) throws IOException, RoutingTable.RouteAlreadyMappedException {
        MediatorConfig config = new MediatorConfig();

        if (configPath!=null && regConfigPath != null) {
            Properties props = new Properties();
            File conf = new File(configPath);
            InputStream in = new FileInputStream(conf);
            props.load(in);
            IOUtils.closeQuietly(in);

            config.setProperties(props);

            File regConf = new File(regConfigPath);
            InputStream regStream = new FileInputStream(regConf);
            RegistrationConfig regConfig = new RegistrationConfig(regStream);
            config.setRegistrationConfig(regConfig);

        } else {
            config.setProperties("mediator.properties");
        }

        config.setName(config.getProperty("mediator.name"));
        config.setServerHost(config.getProperty("mediator.host"));
        config.setServerPort( Integer.parseInt(config.getProperty("mediator.port")) );
        config.setRootTimeout(Integer.parseInt(config.getProperty("mediator.timeout")));

        config.setCoreHost(config.getProperty("core.host"));
        config.setCoreAPIUsername(config.getProperty("core.api.user"));
        config.setCoreAPIPassword(config.getProperty("core.api.password"));
        if (config.getProperty("core.api.port") != null) {
            config.setCoreAPIPort(Integer.parseInt(config.getProperty("core.api.port")));
        }

        config.setRoutingTable(buildRoutingTable());
//        config.setStartupActors(buildStartupActorsConfig());

        // Override registration config from environment
        for (Map.Entry<String, Object> entry : config.getDynamicConfig().entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }

            String environmentKey = entry.getKey().toUpperCase().replace('-', '_');
            String environmentValue = System.getenv(environmentKey);
            if (environmentValue != null) {
                config.getDynamicConfig().put(entry.getKey(), environmentValue);
            }
        }

        if (config.getProperty("mediator.heartbeats")!=null && "true".equalsIgnoreCase(config.getProperty("mediator.heartbeats"))) {
            config.setHeartbeatsEnabled(true);
        }

        return config;
    }

    public static void main(String... args) throws Exception {
        //setup actor system
        final ActorSystem system = ActorSystem.create("mediator");

        final LoggingAdapter log = Logging.getLogger(system, "main");

        log.info("Initializing mediator actors...");

        String configPath = null;
        String regConfigPath = null;
        if (args.length==4 && args[0].equals("--conf") && args[2].equals("--regConf")) {
            configPath = args[1];
            regConfigPath = args[3];
            log.info("Loading mediator configuration from '" + configPath + "'..." +regConfigPath);
        } else {
            log.info("No configuration specified. Using default properties...");
        }

        MediatorConfig config = loadConfig(configPath, regConfigPath);
        final MediatorServer server = new MediatorServer(system, config);

        //shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                log.info("Shutting down mediator");
                server.stop();
                system.shutdown();
            }
        });

        log.info("Starting mediator server...");
        server.start();

        log.info(String.format("%s listening on %s:%s", config.getName(), config.getServerHost(), config.getServerPort()));
        Thread.currentThread().join();
    }
}

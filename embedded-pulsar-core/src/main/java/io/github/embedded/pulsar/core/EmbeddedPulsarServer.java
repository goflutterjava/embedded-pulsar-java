package io.github.embedded.pulsar.core;

import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.pulsar.PulsarStandalone;
import org.apache.pulsar.PulsarStandaloneBuilder;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.common.naming.TopicVersion;
import org.apache.pulsar.zookeeper.LocalBookkeeperEnsemble;
import org.assertj.core.util.Files;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class EmbeddedPulsarServer {

    private final File bkDir;

    private final int bkPort;

    private final File zkDir;

    private final int zkPort;

    private final int webPort;

    private final int tcpPort;

    private final PulsarStandalone pulsarStandalone;

    public EmbeddedPulsarServer() {
        this(new EmbeddedPulsarConfig());
    }

    public EmbeddedPulsarServer(EmbeddedPulsarConfig embeddedPulsarConfig) {
        try {
            this.bkDir = Files.newTemporaryFolder();
            this.bkDir.deleteOnExit();
            if (embeddedPulsarConfig.getBkPort() == 0) {
                this.bkPort = SocketUtil.getFreePort();
            } else {
                this.bkPort = embeddedPulsarConfig.getBkPort();
            }
            this.zkDir = Files.newTemporaryFolder();
            this.zkDir.deleteOnExit();
            if (embeddedPulsarConfig.getZkPort() == 0) {
                this.zkPort = SocketUtil.getFreePort();
            } else {
                this.zkPort = embeddedPulsarConfig.getZkPort();
            }
            LocalBookkeeperEnsemble bkEnsemble = new LocalBookkeeperEnsemble(
                    1, zkPort, bkPort, zkDir.toString(),
                    bkDir.toString(), false, "127.0.0.1");
            ServerConfiguration bkConf = new ServerConfiguration();
            bkConf.setJournalRemovePagesFromCache(false);
            log.info("begin to start bookkeeper");
            bkEnsemble.startStandalone(bkConf, false);
            this.webPort = SocketUtil.getFreePort();
            this.tcpPort = SocketUtil.getFreePort();
            this.pulsarStandalone = PulsarStandaloneBuilder
                    .instance()
                    .withZkPort(zkPort)
                    .withNumOfBk(1)
                    .withOnlyBroker(true)
                    .build();
            ServiceConfiguration standaloneConfig = this.pulsarStandalone.getConfig();
            standaloneConfig.setWebServicePort(Optional.of(webPort));
            standaloneConfig.setBrokerServicePort(Optional.of(tcpPort));
            standaloneConfig.setManagedLedgerDefaultEnsembleSize(1);
            standaloneConfig.setManagedLedgerDefaultWriteQuorum(1);
            standaloneConfig.setManagedLedgerDefaultAckQuorum(1);
            standaloneConfig.setAllowAutoTopicCreation(embeddedPulsarConfig.isAllowAutoTopicCreation());
            standaloneConfig.setAllowAutoTopicCreationType(embeddedPulsarConfig.getAutoTopicCreationType());
            standaloneConfig.setDefaultNumPartitions(embeddedPulsarConfig.getAutoCreateTopicPartitionNum());
            this.pulsarStandalone.setConfig(standaloneConfig);
        } catch (Throwable e) {
            log.error("exception is ", e);
            throw new IllegalStateException("start pulsar standalone failed");
        }
    }

    public void start() throws Exception {
        this.pulsarStandalone.start();
        long start = System.currentTimeMillis();
        while (true) {
            try {
                healthcheck();
                log.info("started pulsar");
                break;
            } catch (Exception e) {
                if (System.currentTimeMillis() - start > 3*60_000) {
                    log.error("start pulsar timeout, stopping pulsar");
                    this.pulsarStandalone.close();
                    break;
                }
                log.info("starting pulsar....");
                TimeUnit.SECONDS.sleep(10);
            }
        }
    }

    private void healthcheck() throws Exception {
        try (PulsarAdmin admin = getDefaultPulsarAdmin()) {
            admin.brokers().healthcheck(TopicVersion.V1);
        }
    }

    public PulsarAdmin getDefaultPulsarAdmin() throws PulsarClientException {
        return PulsarAdmin.builder().serviceHttpUrl("http://locahost:" + this.webPort).build();
    }

    public int getWebPort() {
        return webPort;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public void close() throws Exception {
        this.pulsarStandalone.close();
    }
}

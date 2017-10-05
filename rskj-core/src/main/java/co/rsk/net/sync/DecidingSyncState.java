package co.rsk.net.sync;

import javax.annotation.Nonnull;
import java.time.Duration;

public class DecidingSyncState implements SyncState {
    private Duration timeElapsed = Duration.ZERO;
    private SyncConfiguration syncConfiguration;
    private SyncEventsHandler syncEventsHandler;
    private PeersInformation knownPeers;

    public DecidingSyncState(SyncConfiguration syncConfiguration, SyncEventsHandler syncEventsHandler, PeersInformation knownPeers) {
        this.syncConfiguration = syncConfiguration;
        this.syncEventsHandler = syncEventsHandler;
        this.knownPeers = knownPeers;
    }

    @Nonnull
    @Override
    public SyncStatesIds getId() {
        return SyncStatesIds.DECIDING;
    }

    @Override
    public void newPeerStatus() {
        if (knownPeers.count() >= syncConfiguration.getExpectedPeers()) {
            syncEventsHandler.canStartSyncing();
        }
    }

    @Override
    public void tick(Duration duration) {
        timeElapsed = timeElapsed.plus(duration);
        if (knownPeers.countIf(s -> !s.isExpired(syncConfiguration.getExpirationTimePeerStatus())) > 0 &&
                timeElapsed.compareTo(syncConfiguration.getTimeoutWaitingPeers()) >= 0) {

            syncEventsHandler.canStartSyncing();
        } else {
            knownPeers.cleanExpired(syncConfiguration.getExpirationTimePeerStatus());
        }
    }
}

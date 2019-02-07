/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.peg.whitelist;

import co.rsk.bitcoinj.core.LegacyAddress;
import co.rsk.bitcoinj.core.Coin;
import com.google.common.primitives.UnsignedBytes;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents a lock whitelist
 * for btc lock transactions.
 * It's basically a list of btc addresses
 * with operations to manipulate and query it.
 *
 * @author Ariel Mendelzon
 */
public class LockWhitelist {

    private static final Comparator<LegacyAddress> LEXICOGRAPHICAL_COMPARATOR
        = Comparator.comparing(LegacyAddress::getHash160, UnsignedBytes.lexicographicalComparator());

    private SortedMap<LegacyAddress, LockWhitelistEntry> whitelistedAddresses;
    private int disableBlockHeight;

    public LockWhitelist(Map<LegacyAddress, LockWhitelistEntry> whitelistedAddresses) {
        this(whitelistedAddresses, Integer.MAX_VALUE);
    }

    public LockWhitelist(Map<LegacyAddress, LockWhitelistEntry> whitelistedAddresses, int disableBlockHeight) {
        // Save a copy so that this can't be modified from the outside
        SortedMap<LegacyAddress, LockWhitelistEntry> sortedWhitelistedAddresses = new TreeMap<>(LEXICOGRAPHICAL_COMPARATOR);
        sortedWhitelistedAddresses.putAll(whitelistedAddresses);
        this.whitelistedAddresses = sortedWhitelistedAddresses;
        this.disableBlockHeight = disableBlockHeight;
    }

    public boolean isWhitelisted(LegacyAddress address) {
        return whitelistedAddresses.containsKey(address);
    }

    public boolean isWhitelisted(byte[] address) {
        return whitelistedAddresses.keySet().stream()
                .map(LegacyAddress::getHash160)
                .anyMatch(hash -> Arrays.equals(hash, address));
    }

    public boolean isWhitelistedFor(LegacyAddress address, Coin amount, int height) {
        if (height > disableBlockHeight) {
            // Whitelist disabled
            return true;
        }

        LockWhitelistEntry entry = this.whitelistedAddresses.get(address);
        return (entry != null && entry.canLock(amount));
    }

    public Integer getSize() {
        return whitelistedAddresses.size();
    }

    public List<LegacyAddress> getAddresses() {
        // Return a copy so that this can't be modified from the outside
        return new ArrayList<>(whitelistedAddresses.keySet());
    }

    public <T extends LockWhitelistEntry> List<T> getAll(Class<T> type) {
        return whitelistedAddresses.values().stream()
                .filter(e -> e.getClass() == type)
                .map(type::cast)
                .collect(Collectors.toList());
    }

    public List<LockWhitelistEntry> getAll() {
        // Return a copy so that this can't be modified from the outside
        return new ArrayList<>(whitelistedAddresses.values());
    }

    public LockWhitelistEntry get(LegacyAddress address) {
        return this.whitelistedAddresses.get(address);
    }

    public boolean put(LegacyAddress address, LockWhitelistEntry entry) {
        if (whitelistedAddresses.containsKey(address)) {
            return false;
        }

        whitelistedAddresses.put(address, entry);
        return true;
    }

    public boolean remove(LegacyAddress address) {
        return whitelistedAddresses.remove(address) != null;
    }

    /**
     * Marks the whitelisted address as consumed. This will reduce the number of usages, and if it gets down to zero remaining usages it will remove the address
     * @param address
     */
    public void consume(LegacyAddress address) {
        LockWhitelistEntry entry = whitelistedAddresses.get(address);
        if (entry == null) {
            return;
        }
        entry.consume();

        if (entry.isConsumed()) {
            this.remove(address);
        }
    }

    public int getDisableBlockHeight() {
        return disableBlockHeight;
    }

    public void setDisableBlockHeight(int disableBlockHeight) {
        this.disableBlockHeight = disableBlockHeight;
    }

    public boolean isDisableBlockSet() {
        return disableBlockHeight < Integer.MAX_VALUE;
    }
}

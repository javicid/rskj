/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
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

package co.rsk.logfilter;

import org.ethereum.core.Block;
import org.ethereum.core.Bloom;
import org.ethereum.db.BlockStore;

/**
 * Created by ajlopez on 29/09/2019.
 */
public class BlocksBloomBuilder {
    private final BlocksBloomStore blocksBloomStore;
    private final BlockStore blockStore;

    private BlocksBloom blocksBloomInProcess = null;

    public BlocksBloomBuilder(BlocksBloomStore blocksBloomStore, BlockStore blockStore) {
        this.blocksBloomStore = blocksBloomStore;
        this.blockStore = blockStore;
    }

    public BlocksBloom getBlocksBloomInProcess() {
        return this.blocksBloomInProcess;
    }

    public void processNewBlockNumber(long newBlockNumber) {
        long blockNumber = newBlockNumber - this.blocksBloomStore.getNoConfirmations();

        addBlockNumber(blockNumber);
    }

    private void addBlockNumber(long blockNumber) {
        if (this.blocksBloomStore.hasBlockNumber(blockNumber)) {
            return;
        }

        long fromBlock;

        if (this.blocksBloomInProcess == null) {
            this.blocksBloomInProcess = new BlocksBloom();
            fromBlock = this.blocksBloomStore.firstNumberInRange(blockNumber);
        }
        else {
            fromBlock = this.blocksBloomInProcess.toBlock() + 1;
        }

        if (this.blocksBloomInProcess.hasBlockBloom(blockNumber)) {
            return;
        }

        for (long nb = fromBlock; nb <= blockNumber; nb++) {
            Bloom bloom;

            if (nb > 0) {
                bloom = new Bloom(this.blockStore.getChainBlockByNumber(nb).getLogBloom());
            }
            else {
                bloom = new Bloom();
            }

            this.blocksBloomInProcess.addBlockBloom(nb, bloom);
        }
    }
}

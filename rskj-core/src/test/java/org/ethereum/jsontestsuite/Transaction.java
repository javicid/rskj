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

package org.ethereum.jsontestsuite;

import static org.ethereum.util.ByteUtil.toHexString;

import java.math.BigInteger;
import org.json.simple.JSONObject;

/**
 * @author Roman Mandeleil
 * @since 28.06.2014
 */
public class Transaction {

    byte[] data;
    byte[] gasLimit;
    long gasPrice;
    long nonce;
    byte[] secretKey;
    byte[] to;
    long value;

    /* e.g.
        "transaction" : {
                "data" : "",
                "gasLimit" : "10000",
                "gasPrice" : "1",
                "nonce" : "0",
                "secretKey" : "45a915e4d060149eb4365960e6a7a45f334393093061116b197e3240065ff2d8",
                "to" : "095e7baea6a6c7c4c2dfeb977efac326af552d87",
                "value" : "100000"
    }
    */

    public Transaction(JSONObject callCreateJSON) {

        String dataStr = callCreateJSON.get("data").toString();
        String gasLimitStr =
                org.ethereum.json.Utils.parseUnidentifiedBase(
                        callCreateJSON.get("gasLimit").toString());
        String gasPriceStr =
                org.ethereum.json.Utils.parseUnidentifiedBase(
                        callCreateJSON.get("gasPrice").toString());
        String nonceStr = callCreateJSON.get("nonce").toString();
        String secretKeyStr = callCreateJSON.get("secretKey").toString();
        String toStr = callCreateJSON.get("to").toString();
        String valueStr = callCreateJSON.get("value").toString();

        this.data = org.ethereum.json.Utils.parseData(dataStr);
        this.gasLimit =
                !gasLimitStr.isEmpty() ? new BigInteger(gasLimitStr).toByteArray() : new byte[] {0};
        this.gasPrice = org.ethereum.json.Utils.parseLong(gasPriceStr);
        this.nonce = org.ethereum.json.Utils.parseLong(nonceStr);
        this.secretKey = org.ethereum.json.Utils.parseData(secretKeyStr);
        this.to = org.ethereum.json.Utils.parseData(toStr);
        this.value = org.ethereum.json.Utils.parseLong(valueStr);
    }

    public byte[] getData() {
        return data;
    }

    public byte[] getGasLimit() {
        return gasLimit;
    }

    public long getGasPrice() {
        return gasPrice;
    }

    public long getNonce() {
        return nonce;
    }

    public byte[] getSecretKey() {
        return secretKey;
    }

    public byte[] getTo() {
        return to;
    }

    public long getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "Transaction{"
                + "data="
                + toHexString(data)
                + ", gasLimit="
                + gasLimit
                + ", gasPrice="
                + gasPrice
                + ", nonce="
                + nonce
                + ", secretKey="
                + toHexString(secretKey)
                + ", to="
                + toHexString(to)
                + ", value="
                + value
                + '}';
    }
}

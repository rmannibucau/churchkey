/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.tomitribe.churchkey.pem;

import org.tomitribe.churchkey.Key;
import org.tomitribe.churchkey.asn1.Asn1Object;
import org.tomitribe.churchkey.asn1.DerParser;
import org.tomitribe.churchkey.rsa.Rsa;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPrivateCrtKey;

public class BeginRsaPrivateKey {

    private BeginRsaPrivateKey() {
    }

    public static Key decode(final byte[] bytes) {
        try {
            final DerParser parser = new DerParser(bytes);

            final Asn1Object sequence = parser.read();
            if (sequence.getType() != DerParser.SEQUENCE) {
                throw new IllegalArgumentException("Invalid DER: not a sequence");
            }

            // Parse inside the sequence
            final DerParser p = sequence.getParser();

            p.read(); // Skip version

            final RSAPrivateCrtKey privateKey = Rsa.Private.builder()
                    .modulus(p.read().getInteger())
                    .publicExponent(p.read().getInteger())
                    .privateExponent(p.read().getInteger())
                    .primeP(p.read().getInteger())
                    .primeQ(p.read().getInteger())
                    .primeExponentP(p.read().getInteger())
                    .primeExponentQ(p.read().getInteger())
                    .crtCoefficient(p.read().getInteger())
                    .build()
                    .toKey();

            return new Key(privateKey, Key.Type.PRIVATE, Key.Algorithm.RSA, Key.Format.PEM);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encode(final Key key) {
        return null;
    }

}

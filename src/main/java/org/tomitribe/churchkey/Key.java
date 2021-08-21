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
package org.tomitribe.churchkey;

import org.tomitribe.churchkey.jwk.JwkParser;
import org.tomitribe.churchkey.pem.PemParser;
import org.tomitribe.churchkey.ssh.OpenSSHParser;
import org.tomitribe.churchkey.ssh.SSH2Parser;

import java.util.HashMap;
import java.util.Map;

public class Key {

    private final java.security.Key key;
    private final Type type;
    private final Algorithm algorithm;
    private final Format format;
    private final Map<String, String> attributes = new HashMap<>();

    public Key(final java.security.Key key, final Type type, final Algorithm algorithm, final Format format) {
        this(key, type, algorithm, format, new HashMap<>());
    }

    public Key(final java.security.Key key, final Type type, final Algorithm algorithm, final Format format, final Map<String, String> attributes) {
        this.key = key;
        this.type = type;
        this.algorithm = algorithm;
        this.format = format;
        this.attributes.putAll(attributes);
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public String getAttribute(final String name) {
        return attributes.get(name);
    }

    public java.security.Key getKey() {
        return key;
    }

    public Type getType() {
        return type;
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }

    public Format getFormat() {
        return format;
    }

    // TODO
    // public Key getPublicKey()

    public byte[] encode(final Format format) {
        return format.encode(this);
    }

    public enum Type {
        PUBLIC,
        PRIVATE,
        SECRET
    }

    public enum Algorithm {
        RSA, DSA, EC, OCT
    }

    public enum Format {
        JWK(new JwkParser()),
        OPENSSH(new OpenSSHParser()),
        SSH2(new SSH2Parser()),
        PEM(new PemParser()),
        ;

        private final Parser parser;

        Format(final Parser parser) {
            this.parser = parser;
        }

        public byte[] encode(final Key key) {
            return parser.encode(key);
        }

        public Key decode(final byte[] bytes) {
            return parser.decode(bytes);
        }

        public interface Parser {
            Key decode(final byte[] bytes);

            byte[] encode(final Key key);
        }
    }
}

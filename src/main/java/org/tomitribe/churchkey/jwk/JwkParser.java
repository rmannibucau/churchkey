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
package org.tomitribe.churchkey.jwk;

import org.tomitribe.churchkey.Key;
import org.tomitribe.churchkey.dsa.Dsa;
import org.tomitribe.churchkey.ec.Curve;
import org.tomitribe.churchkey.ec.ECParameterSpecs;
import org.tomitribe.churchkey.ec.Ecdsa;
import org.tomitribe.churchkey.ec.UnsupportedCurveException;
import org.tomitribe.churchkey.util.Bytes;
import org.tomitribe.churchkey.util.Utils;
import org.tomitribe.util.IO;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonReaderFactory;
import javax.json.JsonValue;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JwkParser implements Key.Format.Parser {

    @Override
    public Key decode(final byte[] bytes) {
        byte[] decoded = normalize(bytes);
        if (!Utils.startsWith("{", decoded)) return null;

        final String rawJson = new String(decoded);

        final HashMap<String, Object> config = new HashMap<>();
        config.put("org.apache.johnzon.buffer-strategy", "BY_INSTANCE");
        final JsonReaderFactory factory = Json.createReaderFactory(config);
        final JsonReader reader = factory.createReader(IO.read(decoded));

        try {
            final JsonObject jsonObject = reader.readObject();
            final JsonObject jwk = getJwk(jsonObject);

            final String kty;

            if (!jwk.containsKey("kty")) {
                throw new MissingKtyException();
            }

            kty = jwk.getString("kty");

            if ("RSA".equalsIgnoreCase(kty)) {
                return asRsaKey(jwk);
            }

            if ("OCT".equalsIgnoreCase(kty)) {
                return asOctKey(jwk);
            }

            if ("DSA".equals(kty)) {
                return asDsaKey(jwk);
            }

            if ("EC".equals(kty)) {
                return asEcKey(jwk);
            }

            throw new UnsupportedKtyAlgorithmException(kty);

        } catch (Exception e) {
            throw new InvalidJwkException(e, rawJson);
        }
    }

    private Key asDsaKey(final JsonObject jsonObject) {
        final Jwk jwk = new Jwk(jsonObject);

        final BigInteger p = jwk.getBigInteger("p");
        final BigInteger q = jwk.getBigInteger("q");
        final BigInteger g = jwk.getBigInteger("g");
        final BigInteger x = jwk.getBigInteger("x");
        final BigInteger y = jwk.getBigInteger("y");

        final List<String> missing = new ArrayList<>();
        if (p == null) missing.add("p");
        if (q == null) missing.add("q");
        if (g == null) missing.add("g");
        if (missing.size() != 0) {
            throw new InvalidJwkKeySpecException("DSA", missing);
        }

        if (x != null) {
            final Dsa.Private build = Dsa.Private.builder()
                    .p(p)
                    .q(q)
                    .g(g)
                    .x(x)
                    .build();
            final DSAPrivateKey privateKey = build.toKey();
            final DSAPublicKey publicKey = build.toPublic().toKey();

            final Map<String, String> attributes = getAttributes(jsonObject, "kty", "p", "q", "q", "x", "y");
            return new Key(privateKey, publicKey, Key.Type.PRIVATE, Key.Algorithm.DSA, Key.Format.JWK, attributes);
        }

        if (y != null) {
            final DSAPublicKey publicKey = Dsa.Public.builder()
                    .p(p)
                    .q(q)
                    .g(g)
                    .y(y)
                    .build()
                    .toKey();
            final Map<String, String> attributes = getAttributes(jsonObject, "kty", "p", "q", "q", "x", "y");
            return new Key(publicKey, Key.Type.PUBLIC, Key.Algorithm.DSA, Key.Format.JWK, attributes);
        }

        throw new InvalidJwkKeySpecException("DSA", "x", "y");
    }

    /**
     * {
     *   "kty": "EC",
     *   "crv": "P-256",
     *   "x": "MKBCTNIcKUSDii11ySs3526iDZ8AiTo7Tu6KPAqv7D4",
     *   "y": "4Etl6SRW2YiLUrN5vfvVHuhp7x8PxltmWWlbbM4IFyM",
     *   "d": "870MB6gfuTJ4HtUnUvYMyJpr5eUZNP4Bk43bVdj3eAE",
     *   "use": "enc",
     *   "kid": "1"
     * }
     */
    private Key asEcKey(final JsonObject jsonObject) {
        final Jwk jwk = new Jwk(jsonObject);

        final String crv = jwk.getString("crv");
        final BigInteger d = jwk.getBigInteger("d");
        final BigInteger x = jwk.getBigInteger("x");
        final BigInteger y = jwk.getBigInteger("y");

        if (crv == null) throw new InvalidJwkKeySpecException("EC", "crv");

        final Curve curve = Curve.resolve(crv);

        if (d != null) {
            final Ecdsa.Private build = Ecdsa.Private.builder()
                    .curve(curve)
                    .d(d)
                    .x(x)
                    .y(y)
                    .build();

            final ECPrivateKey privateKey = build.toKey();
            final ECPublicKey publicKey = build.getX() != null && build.getY() != null ? build.toPublic().toKey() : null;
            final Map<String, String> attributes = getAttributes(jsonObject, "kty", "crv", "d");
            return new Key(privateKey, publicKey, Key.Type.PRIVATE, Key.Algorithm.EC, Key.Format.JWK, attributes);
        }

        final List<String> missing = new ArrayList<String>();
        if (y == null) missing.add("y");
        if (x == null) missing.add("x");
        if (missing.size() != 0) {
            throw new InvalidJwkKeySpecException("EC", missing);
        }
        final ECPublicKey publicKey = Ecdsa.Public.builder()
                .curve(curve)
                .x(x)
                .y(y)
                .build()
                .toKey();
        final Map<String, String> attributes = getAttributes(jsonObject, "kty", "crv", "x", "y");
        return new Key(publicKey, Key.Type.PUBLIC, Key.Algorithm.EC, Key.Format.JWK, attributes);
    }

    private Key asRsaKey(final JsonObject jsonObject) throws NoSuchAlgorithmException, InvalidKeySpecException {
        final Jwk jwk = new Jwk(jsonObject);

        final BigInteger modulus = jwk.getBigInteger("n");
        final BigInteger publicExp = jwk.getBigInteger("e");
        final BigInteger privateExp = jwk.getBigInteger("d");
        final BigInteger primeP = jwk.getBigInteger("p");
        final BigInteger primeQ = jwk.getBigInteger("q");
        final BigInteger primeExponentP = jwk.getBigInteger("dp");
        final BigInteger primeExponentQ = jwk.getBigInteger("dq");
        final BigInteger crtCoef = jwk.getBigInteger("qi");

        final RSAPublicKeySpec rsaPublicKeySpec = new RSAPublicKeySpec(modulus, publicExp);
        final RSAPrivateCrtKeySpec rsaPrivateKeySpec = new RSAPrivateCrtKeySpec(modulus, publicExp, privateExp, primeP, primeQ, primeExponentP, primeExponentQ, crtCoef);

        checkPublicKey(rsaPublicKeySpec);
        checkPrivateKey(rsaPrivateKeySpec);

        final KeyFactory result = KeyFactory.getInstance("RSA");
        final PublicKey publicKey = result.generatePublic(rsaPublicKeySpec);

        if (privateExp != null) {
            final PrivateKey privateKey = result.generatePrivate(rsaPrivateKeySpec);
            final Map<String, String> attributes = getAttributes(jsonObject, "kty", "n", "e", "d", "p", "q", "dp", "dq", "qi");
            return new Key(privateKey, publicKey, Key.Type.PRIVATE, Key.Algorithm.RSA, Key.Format.JWK, attributes);
        }

        final Map<String, String> attributes = getAttributes(jsonObject, "kty", "n", "e");
        return new Key(publicKey, Key.Type.PUBLIC, Key.Algorithm.RSA, Key.Format.JWK, attributes);
    }

    private void toRsaKey(final Key key, final JsonObjectBuilder jwk) {

        if (key.getKey() instanceof RSAPrivateCrtKey) {
            final RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) key.getKey();
            jwk.add("n", encode(privateKey.getModulus()));
            jwk.add("e", encode(privateKey.getPublicExponent()));
            jwk.add("d", encode(privateKey.getPrivateExponent()));
            jwk.add("p", encode(privateKey.getPrimeP()));
            jwk.add("q", encode(privateKey.getPrimeQ()));
            jwk.add("dp", encode(privateKey.getPrimeExponentP()));
            jwk.add("dq", encode(privateKey.getPrimeExponentQ()));
            jwk.add("qi", encode(privateKey.getCrtCoefficient()));
        } else if (key.getKey() instanceof RSAPrivateKey) {
            final RSAPrivateKey privateKey = (RSAPrivateKey) key.getKey();
            jwk.add("n", encode(privateKey.getModulus()));
            jwk.add("d", encode(privateKey.getPrivateExponent()));
        } else if (key.getKey() instanceof RSAPublicKey) {
            final RSAPublicKey publicKey = (RSAPublicKey) key.getKey();
            jwk.add("n", encode(publicKey.getModulus()));
            jwk.add("e", encode(publicKey.getPublicExponent()));
        } else {
            throw new UnsupportedOperationException("Unkown RSA Key type: " + key.getKey().getClass().getName());
        }
        jwk.add("kty", "RSA");
    }

    private void toDsaKey(final Key key, final JsonObjectBuilder jwk) {

        if (key.getKey() instanceof DSAPrivateKey) {
            final DSAPrivateKey privateKey = (DSAPrivateKey) key.getKey();
            jwk.add("x", encode(privateKey.getX()));
            jwk.add("p", encode(privateKey.getParams().getP()));
            jwk.add("q", encode(privateKey.getParams().getQ()));
            jwk.add("g", encode(privateKey.getParams().getG()));
        } else if (key.getKey() instanceof DSAPublicKey) {
            final DSAPublicKey privateKey = (DSAPublicKey) key.getKey();
            jwk.add("y", encode(privateKey.getY()));
            jwk.add("p", encode(privateKey.getParams().getP()));
            jwk.add("q", encode(privateKey.getParams().getQ()));
            jwk.add("g", encode(privateKey.getParams().getG()));
        } else {
            throw new UnsupportedOperationException("Unkown DSA Key type: " + key.getKey().getClass().getName());
        }
        jwk.add("kty", "DSA");
    }

    private void toEcKey(final Key key, final JsonObjectBuilder jwk) {

        if (key.getKey() instanceof ECPrivateKey) {
            final ECPrivateKey privateKey = (ECPrivateKey) key.getKey();
            jwk.add("d", encode(privateKey.getS()));
            jwk.add("crv", curveName(privateKey.getParams()));
            if (key.getPublicKey() != null) {
                final ECPublicKey publicKey = (ECPublicKey) key.getPublicKey().getKey();
                final ECPoint point = publicKey.getW();
                jwk.add("y", encode(point.getAffineY()));
                jwk.add("x", encode(point.getAffineX()));
            }
        } else if (key.getKey() instanceof ECPublicKey) {
            final ECPublicKey publicKey = (ECPublicKey) key.getKey();
            final ECPoint point = publicKey.getW();
            jwk.add("y", encode(point.getAffineY()));
            jwk.add("x", encode(point.getAffineX()));
            jwk.add("crv", curveName(publicKey.getParams()));
        } else {
            throw new UnsupportedOperationException("Unkown EC Key type: " + key.getKey().getClass().getName());
        }
        jwk.add("kty", "EC");
    }

    private String curveName(final ECParameterSpec spec) {
        // Try the most common cases first
        if (Curve.p256.isEqual(spec)) return "P-256";
        if (Curve.p384.isEqual(spec)) return "P-384";
        if (Curve.p521.isEqual(spec)) return "P-521";

        for (final Curve curve : Curve.values()) {
            if (!curve.isEqual(spec)) continue;

            return curve.getName();
        }

        // Unsupported curve
        // Print the curve information in the exception
        final String s = ECParameterSpecs.toString(spec);
        throw new UnsupportedCurveException(String.format("The specified ECParameterSpec has no known name.  Params:%n%s", s));
    }

    private Key asOctKey(final JsonObject jwkObject) {
        final Jwk jwk = new Jwk(jwkObject);

        final byte[] keyBytes = jwk.getBytes("k");
        final String alg = jwk.getString("alg", "HS256").toUpperCase();
        final String jmvAlg = alg.replace("HS", "HmacSHA");
        final SecretKeySpec keySpec = new SecretKeySpec(keyBytes, jmvAlg);

        final Map<String, String> attributes = getAttributes(jwkObject, "kty", "k");
        return new Key(keySpec, Key.Type.SECRET, Key.Algorithm.OCT, Key.Format.JWK, attributes);
    }

    private void toOctKey(final Key key, final JsonObjectBuilder jwk) {

        if (key.getKey() instanceof SecretKey) {
            final SecretKey publicKey = (SecretKey) key.getKey();
            jwk.add("k", encode(publicKey.getEncoded()));
        } else {
            throw new UnsupportedOperationException("Unkown RSA Key type: " + key.getKey().getClass().getName());
        }
        jwk.add("kty", "oct");
    }


    private Map<String, String> getAttributes(final JsonObject jwkObject, final String... excludes) {
        return getAttributes(jwkObject, Arrays.asList(excludes));
    }

    private Map<String, String> getAttributes(final JsonObject jwkObject, final Collection<String> excludes) {
        final Map<String, String> map = new HashMap<>();

        for (final Map.Entry<String, JsonValue> entry : jwkObject.entrySet()) {
            if (excludes.contains(entry.getKey())) continue;
            map.put(entry.getKey(), toString(entry.getValue()));
        }
        return map;
    }

    private String toString(final JsonValue value) {
        switch (value.getValueType()) {
            case STRING:
                final String string = value.toString();
                return string.substring(1, string.length() - 1);
            case NULL:
                return null;
            default:
                return value.toString();
        }
    }


    private void checkPublicKey(final RSAPublicKeySpec spec) {
        final List<String> missing = new ArrayList<>();

        if (spec.getModulus() == null) missing.add("n");
        if (spec.getPublicExponent() == null) missing.add("e");

        if (missing.size() > 0) throw new InvalidJwkKeySpecException("rsa", missing);
    }

    private void checkPrivateKey(final RSAPrivateCrtKeySpec spec) {
        final List<String> missing = new ArrayList<>();

        if (spec.getPrivateExponent() == null) missing.add("d");
        if (spec.getPrimeP() == null) missing.add("p");
        if (spec.getPrimeQ() == null) missing.add("q");
        if (spec.getPrimeExponentP() == null) missing.add("dp");
        if (spec.getPrimeExponentQ() == null) missing.add("dq");
        if (spec.getCrtCoefficient() == null) missing.add("qi");

        /**
         * We want them to supply either all or none of the private key data
         */
        if (missing.size() == 6) return; // they've supplied none - good
        if (missing.size() == 0) return; // they've supplied all - good

        /**
         * They supplied just some.  This doesn't work and isn't likely what they want
         */
        throw new InvalidJwkKeySpecException("rsa", missing);
    }

    public static String encode(final BigInteger bigInteger) {
        final Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();

        final byte[] bytes = Bytes.trim(bigInteger.toByteArray());
        return urlEncoder.encodeToString(bytes);
    }

    public static String encode(final byte[] bytes) {
        final Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
        return urlEncoder.encodeToString(bytes);
    }

    private static class Jwk {

        private final JsonObject jwk;

        public Jwk(final JsonObject jwk) {
            this.jwk = jwk;
        }

        public BigInteger getBigInteger(final String name) {
            if (!jwk.containsKey(name)) return null;
            final String string = jwk.getString(name);
            final java.util.Base64.Decoder urlDecoder = java.util.Base64.getUrlDecoder();
            final byte[] bytes = urlDecoder.decode(string);
            return new BigInteger(1, bytes);
        }

        public byte[] getBytes(final String name) {
            if (!jwk.containsKey(name)) return null;
            final String string = jwk.getString(name);
            final java.util.Base64.Decoder urlDecoder = java.util.Base64.getUrlDecoder();
            return urlDecoder.decode(string);
        }

        public String getString(final String s) {
            return jwk.getString(s);
        }

        public String getString(final String s, final String s1) {
            return jwk.getString(s, s1);
        }
    }

    private JsonObject getJwk(final JsonObject jsonObject) {
        if (jsonObject.containsKey("keys")) {
            return getJwkFromJwks(jsonObject);
        }

        if (jsonObject.containsKey("kty")) {
            return jsonObject;
        }

        throw new UnknownJsonFormatFoundException();
    }

    private JsonObject getJwkFromJwks(final JsonObject jwks) {
        final JsonValue keys = jwks.getValue("keys");

        if (keys == null) {
            throw new IllegalArgumentException("Invalid JWKS; 'keys' entry is missing.");
        }

        switch (keys.getValueType()) {
            case ARRAY:
                return getFirstJwk(jwks, keys.asJsonArray());
            case OBJECT:
                return keys.asJsonObject();
            default:
                throw new IllegalArgumentException("Invalid JWKS; 'keys' entry should be an array.");

        }
    }

    private JsonObject getFirstJwk(final JsonObject jwks, final JsonArray keys) {
        if (keys.size() == 0) {
            throw new IllegalArgumentException("Invalid JWKS; 'keys' entry is empty.\n" + jwks.toString());
        }

        final JsonValue value = keys.get(0);

        if (!JsonValue.ValueType.OBJECT.equals(value.getValueType())) {
            throw new IllegalArgumentException("Invalid JWKS; 'keys' array should contain jwk objects.\n" + jwks.toString());
        }

        return value.asJsonObject();
    }

    /**
     * Base64 unencode the jwk key if needed
     */
    private byte[] normalize(final byte[] bytes) {
        // Fun optimization, base64 json objects always happen to
        // start with 'e' due to always beginning with "{" in
        // unencoded form.  If it doesn't start with 'e' then it
        // isn't base64 encoded or isn't a jwk.
        if (!Utils.startsWith("e", bytes)) return bytes;
        // But don't try to convert ecdsa SSH keys
        if (Utils.startsWith("ecdsa", bytes)) return bytes;

        return java.util.Base64.getUrlDecoder().decode(bytes);
    }

    @Override
    public byte[] encode(final Key key) {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        for (final Map.Entry<String, String> entry : key.getAttributes().entrySet()) {
            builder.add(entry.getKey(), entry.getValue());
        }

        switch (key.getAlgorithm()) {
            case RSA:
                toRsaKey(key, builder);
                break;

            case DSA:
                toDsaKey(key, builder);
                break;

            case EC:
                toEcKey(key, builder);
                break;

            case OCT:
                toOctKey(key, builder);
                break;

            default:
                throw new UnsupportedOperationException("Cannot encode key type: " + key.getAlgorithm());
        }

        final JsonObject build = builder.build();
        return build.toString().getBytes();
    }

}

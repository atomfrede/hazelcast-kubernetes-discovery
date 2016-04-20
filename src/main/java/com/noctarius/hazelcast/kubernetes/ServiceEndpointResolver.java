/*
 * Copyright (c) 2015, Christoph Engelbert (aka noctarius) and
 * contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.noctarius.hazelcast.kubernetes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.spi.discovery.DiscoveryNode;
import com.hazelcast.spi.discovery.SimpleDiscoveryNode;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;

class ServiceEndpointResolver
        extends HazelcastKubernetesDiscoveryStrategy.EndpointResolver {

    private final String serviceName;
    private final String namespace;

    private final KubernetesClient client;

    public ServiceEndpointResolver(ILogger logger, String serviceName, String namespace, String kubernetesMaster) {
        super(logger);

        logger.info("Kubernetes Master: " + kubernetesMaster);
        this.serviceName = serviceName;
        this.namespace = namespace;

        String accountToken = getAccountToken();
        logger.info("Kubernetes Discovery: Bearer Token { " + accountToken + " }");
        Config config = new ConfigBuilder().withOauthToken(accountToken)
                .withMasterUrl(kubernetesMaster)
                .build();

        logger.info("Kubernetes config: " +config.getMasterUrl());
        this.client = new DefaultKubernetesClient(config);

        logger.info("MasterURL in client: " + this.client.getMasterUrl());
    }

    List<DiscoveryNode> resolve() {
        Endpoints endpoints = client.endpoints().inNamespace(namespace).withName(serviceName).get();
        if (endpoints == null) {
            return Collections.emptyList();
        }

        List<DiscoveryNode> discoveredNodes = new ArrayList<DiscoveryNode>();
        for (EndpointSubset endpointSubset : endpoints.getSubsets()) {
            for (EndpointAddress endpointAddress : endpointSubset.getAddresses()) {
                Map<String, Object> properties = endpointAddress.getAdditionalProperties();

                String ip = endpointAddress.getIp();
                InetAddress inetAddress = mapAddress(ip);
                int port = getServicePort(properties);

                Address address = new Address(inetAddress, port);
                discoveredNodes.add(new SimpleDiscoveryNode(address, properties));
            }
        }

        return discoveredNodes;
    }

    @Override
    void destroy() {
        client.close();
    }

    private String getAccountToken() {
        try {
            String tokenFile = "/var/run/secrets/kubernetes.io/serviceaccount/token";
            File file = new File(tokenFile);
            byte[] data = new byte[(int) file.length()];
            InputStream is = new FileInputStream(file);
            is.read(data);
            return new String(data);

        } catch (IOException e) {
            throw new RuntimeException("Could not get token file", e);
        }
    }
}

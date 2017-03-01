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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hurence.logisland.agent.rest.client;

import com.hurence.logisland.agent.rest.model.*;
import org.glassfish.jersey.jackson.JacksonFeature;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import java.util.Date;


/**
 * http://www.hascode.com/2013/12/jax-rs-2-0-rest-client-features-by-example/
 */
public class RestTopicsApiClient implements TopicsApiClient {

    Client client = ClientBuilder.newClient().register(JacksonFeature.class);
    private final String restServiceUrl;

    public RestTopicsApiClient() {
        this("http://localhost:8081");
    }

    public RestTopicsApiClient(String baseUrl) {
        this.restServiceUrl = baseUrl + "/topics";
    }

    @Override
    public Topic addTopic(Topic topic) {
        return client.target(restServiceUrl)
                .request()
                .post(Entity.entity(topic, MediaType.APPLICATION_JSON), Topic.class);

    }

    @Override
    public Topic getTopic(String name) {
        return client.target(restServiceUrl)
                .path("/{topicId}")
                .resolveTemplate("topicId", name)
                .request()
                .get(Topic.class);

    }
}

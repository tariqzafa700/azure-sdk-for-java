// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.azure.cosmos.rx;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.implementation.HttpConstants;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosContainerRequestOptions;
import com.azure.cosmos.models.CosmosDatabaseProperties;
import com.azure.cosmos.models.CosmosPermissionProperties;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.CosmosStoredProcedureProperties;
import com.azure.cosmos.models.CosmosTriggerProperties;
import com.azure.cosmos.models.CosmosUserDefinedFunctionProperties;
import com.azure.cosmos.models.CosmosUserProperties;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.PermissionMode;
import com.azure.cosmos.models.SqlQuerySpec;
import com.azure.cosmos.models.ThroughputProperties;
import com.azure.cosmos.models.TriggerOperation;
import com.azure.cosmos.models.TriggerType;
import com.azure.cosmos.util.CosmosPagedFlux;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class QueryValidationTests extends TestSuiteBase {
    private static final int DEFAULT_NUM_DOCUMENTS = 1000;
    private static final int DEFAULT_PAGE_SIZE = 100;
    private CosmosAsyncDatabase createdDatabase;
    private CosmosAsyncContainer createdContainer;
    private Random random;

    private CosmosAsyncClient client;
    private List<TestObject> createdDocuments = new ArrayList<>();

    @Factory(dataProvider = "clientBuildersWithDirectSession")
    public QueryValidationTests(CosmosClientBuilder clientBuilder) {
        super(clientBuilder);
        random = new Random();
    }

    @BeforeClass(groups = {"simple"}, timeOut = SETUP_TIMEOUT)
    public void beforeClass() throws Exception {
        client = this.getClientBuilder().buildAsyncClient();
        createdDatabase = getSharedCosmosDatabase(client);
        createdContainer = getSharedMultiPartitionCosmosContainer(client);
        truncateCollection(createdContainer);

        createdDocuments.addAll(this.insertDocuments(DEFAULT_NUM_DOCUMENTS, null, createdContainer));
    }

    @Test(groups = {"simple"}, timeOut = TIMEOUT)
    public void orderByQuery() {
        /*
        The idea here is to query documents in pages, query all the documents(with pagesize as num_documents and compare
         the results.
         */
        String query = "select * from c order by c.prop ASC";
        queryWithOrderByAndAssert(
            DEFAULT_PAGE_SIZE,
            DEFAULT_NUM_DOCUMENTS,
            query,
            createdContainer,
            d -> d.getProp(),
            createdDocuments);
    }

    @Test(groups = {"simple"}, timeOut = TIMEOUT)
    public void orderByQueryForLargeCollection() {
        CosmosContainerProperties containerProperties = getCollectionDefinition();
        createdDatabase.createContainer(
            containerProperties,
            ThroughputProperties.createManualThroughput(100000), // Create container with large number physical partitions
            new CosmosContainerRequestOptions()
        ).block();

        CosmosAsyncContainer container = createdDatabase.getContainer(containerProperties.getId());

        int partitionDocCount = 5;
        int pageSize = partitionDocCount + 1;

        String partition1Key = UUID.randomUUID().toString();
        String partition2Key = UUID.randomUUID().toString();

        List<TestObject> documentsInserted = new ArrayList<>();
        documentsInserted.addAll(this.insertDocuments(
            partitionDocCount,
            Collections.singletonList(partition1Key),
            container));
        documentsInserted.addAll(this.insertDocuments(
            partitionDocCount,
            Collections.singletonList(partition2Key),
            container));

        String query = String.format(
            "select * from c where c.mypk in ('%s', '%s') order by c.constantProp DESC",
            partition1Key,
            partition2Key);

        queryWithOrderByAndAssert(
            pageSize,
            partitionDocCount * 2,
            query,
            container,
            d -> d.getConstantProp(),
            documentsInserted);
    }

    @Test(groups = {"simple"}, timeOut = TIMEOUT)
    public void queryOptionNullValidation() {
        String query = "Select top 1 * from c";

        //Database null query option check
        FeedResponse<CosmosDatabaseProperties> databases = client.queryDatabases(query, null).byPage(1).blockFirst();
        assertThat(databases.getResults().size()).isEqualTo(1);
        databases = client.queryDatabases(new SqlQuerySpec(query), null).byPage(1).blockFirst();
        assertThat(databases.getResults().size()).isEqualTo(1);

        //Container null query check
        FeedResponse<CosmosContainerProperties> containers =
            createdDatabase.readAllContainers(null).byPage(1).blockFirst();
        assertThat(containers.getResults().size()).isGreaterThanOrEqualTo(1);
        containers = createdDatabase.queryContainers(query, null).byPage(1).blockFirst();
        assertThat(containers.getResults().size()).isEqualTo(1);
        containers = createdDatabase.queryContainers(new SqlQuerySpec(query), null).byPage(1).blockFirst();
        assertThat(containers.getResults().size()).isEqualTo(1);

        //User null query check
        CosmosUserProperties userProperties = new CosmosUserProperties();
        userProperties.setId(UUID.randomUUID().toString());
        createdDatabase.createUser(userProperties).block();
        FeedResponse<CosmosUserProperties> users = createdDatabase.queryUsers(query, null).byPage(1).blockFirst();
        assertThat(users.getResults().size()).isEqualTo(1);
        users = createdDatabase.queryUsers(new SqlQuerySpec(query), null).byPage(1).blockFirst();
        assertThat(users.getResults().size()).isEqualTo(1);

        //Permission null query check
        CosmosPermissionProperties cosmosPermissionProperties = new CosmosPermissionProperties();
        cosmosPermissionProperties.setContainerName(createdContainer.getId());
        cosmosPermissionProperties.setPermissionMode(PermissionMode.READ);
        cosmosPermissionProperties.setId(UUID.randomUUID().toString());
        createdDatabase.getUser(userProperties.getId()).createPermission(cosmosPermissionProperties, null).block();

        FeedResponse<CosmosPermissionProperties> permissions =
            createdDatabase.getUser(userProperties.getId()).queryPermissions(query, null).byPage(1).blockFirst();
        assertThat(permissions.getResults().size()).isEqualTo(1);

        //Item null query check
        FeedResponse<TestObject> items =
            createdContainer.queryItems(query, null, TestObject.class).byPage(1).blockFirst();
        assertThat(items.getResults().size()).isEqualTo(1);
        items = createdContainer.queryItems(new SqlQuerySpec(query), null, TestObject.class).byPage(1).blockFirst();
        assertThat(items.getResults().size()).isEqualTo(1);

        createdContainer.getScripts().createStoredProcedure(getCosmosStoredProcedureProperties()).block();
        createdContainer.getScripts().createTrigger(getCosmosTriggerProperties()).block();
        createdContainer.getScripts().createUserDefinedFunction(getCosmosUserDefinedFunctionProperties()).block();

        //Sproc null query check
        FeedResponse<CosmosStoredProcedureProperties> sprocs =
            createdContainer.getScripts().queryStoredProcedures(query, null).byPage(1).blockFirst();
        assertThat(sprocs.getResults().size()).isEqualTo(1);
        sprocs =
            createdContainer.getScripts().queryStoredProcedures(new SqlQuerySpec(query), null).byPage(1).blockFirst();
        assertThat(sprocs.getResults().size()).isEqualTo(1);

        //Trigger null query check
        FeedResponse<CosmosTriggerProperties> triggers =
            createdContainer.getScripts().queryTriggers(query, null).byPage(1).blockFirst();
        assertThat(triggers.getResults().size()).isEqualTo(1);
        triggers = createdContainer.getScripts().queryTriggers(new SqlQuerySpec(query), null).byPage(1).blockFirst();
        assertThat(triggers.getResults().size()).isEqualTo(1);

        //Udf null query check
        FeedResponse<CosmosUserDefinedFunctionProperties> udfs =
            createdContainer.getScripts().queryUserDefinedFunctions(query, null).byPage(1).blockFirst();
        assertThat(udfs.getResults().size()).isEqualTo(1);
        udfs =
            createdContainer.getScripts().queryUserDefinedFunctions(new SqlQuerySpec(query), null).byPage(1).blockFirst();
        assertThat(udfs.getResults().size()).isEqualTo(1);

        //Conflict null query check
        try {
            createdContainer.queryConflicts(query, null).byPage(1).blockFirst();
        } catch (CosmosException exception) {
            // It should give bad request exception for not having partition key but not a null pointer.
            assertThat(exception.getStatusCode()).isEqualTo(HttpConstants.StatusCodes.BADREQUEST);
        }
        createdContainer.readAllConflicts(null).byPage(1).blockFirst();
    }

    private <T> List<T> queryWithContinuationTokens(String query, int pageSize, CosmosAsyncContainer container, Class<T> klass) {
        logger.info("querying: " + query);
        String requestContinuation = null;

        List<T> receivedDocuments = new ArrayList<>();
        CosmosQueryRequestOptions options = new CosmosQueryRequestOptions();
        options.setMaxDegreeOfParallelism(2);

        do {
            CosmosPagedFlux<T> queryPagedFlux = container.queryItems(query, options, klass);
            FeedResponse<T> firstPage = queryPagedFlux.byPage(requestContinuation, pageSize).blockFirst();
            assert firstPage != null;
            requestContinuation = firstPage.getContinuationToken();
            receivedDocuments.addAll(firstPage.getResults());
        } while (requestContinuation != null);

        return receivedDocuments;
    }

    private TestObject getDocumentDefinition(String documentId, String partitionKey) {
        // Doing NUM_DOCUMENTS/2 just to ensure there will be good number of repetetions for int value.
        int randInt = random.nextInt(DEFAULT_NUM_DOCUMENTS / 2);

        TestObject doc = new TestObject(documentId, "name" + randInt, randInt, partitionKey);
        return doc;
    }

    private <T> List<String> sortTestObjectsAndCollectIds(
        List<TestObject> createdDocuments, Function<TestObject, T> extractProp, Comparator<T> comparer) {
        return createdDocuments.stream()
            .sorted((d1, d2) -> comparer.compare(extractProp.apply(d1), extractProp.apply(d2)))
            .map(d -> d.getId()).collect(Collectors.toList());
    }

    private List<TestObject> insertDocuments(int documentCount, List<String> partitionKeys, CosmosAsyncContainer container) {
        List<TestObject> documentsToInsert = new ArrayList<>();

        for (int i = 0; i < documentCount; i++) {
            documentsToInsert.add(
                getDocumentDefinition(
                    UUID.randomUUID().toString(),
                    partitionKeys == null ? UUID.randomUUID().toString() : partitionKeys.get(random.nextInt(partitionKeys.size()))));
        }

        List<TestObject> documentInserted = bulkInsertBlocking(container, documentsToInsert);

        waitIfNeededForReplicasToCatchUp(this.getClientBuilder());

        return documentInserted;
    }

    private <T extends Comparable<T>> void queryWithOrderByAndAssert(
        int pageSize,
        int documentCount,
        String query,
        CosmosAsyncContainer container,
        Function<TestObject, T> extractProp,
        List<TestObject> documentsInserted) {

        List<TestObject> documentsPaged = queryWithContinuationTokens(query, pageSize, container, TestObject.class);

        List<TestObject> allDocuments = queryWithContinuationTokens(query, documentCount, container, TestObject.class);

        Comparator<T> validatorComparator = Comparator.nullsFirst(Comparator.<T>naturalOrder());
        List<String> expectedResourceIds = sortTestObjectsAndCollectIds(documentsInserted,
            extractProp,
            validatorComparator);

        List<String> docIds1 = documentsPaged.stream().map(TestObject::getId).collect(Collectors.toList());
        List<String> docIds2 = allDocuments.stream().map(TestObject::getId).collect(Collectors.toList());

        assertThat(docIds2).containsExactlyInAnyOrderElementsOf(expectedResourceIds);
        assertThat(docIds1).containsExactlyElementsOf(docIds2);
    }

    private static CosmosUserDefinedFunctionProperties getCosmosUserDefinedFunctionProperties() {
        CosmosUserDefinedFunctionProperties udf =
            new CosmosUserDefinedFunctionProperties(UUID.randomUUID().toString(), "function() {var x = 10;}");
        return udf;
    }

    private static CosmosTriggerProperties getCosmosTriggerProperties() {
        CosmosTriggerProperties trigger = new CosmosTriggerProperties(UUID.randomUUID().toString(), "function() {var " +
            "x = 10;}");
        trigger.setTriggerOperation(TriggerOperation.CREATE);
        trigger.setTriggerType(TriggerType.PRE);
        return trigger;
    }

    private static CosmosStoredProcedureProperties getCosmosStoredProcedureProperties() {
        CosmosStoredProcedureProperties storedProcedureDef =
            new CosmosStoredProcedureProperties(UUID.randomUUID().toString(), "function() {var x = 10;}");
        return storedProcedureDef;
    }

    static class TestObject {
        String id;
        String name;
        int prop;
        String mypk;
        String constantProp = "constantProp";

        public TestObject() {
        }

        public TestObject(String id, String name, int prop, String mypk) {
            this.id = id;
            this.name = name;
            this.prop = prop;
            this.mypk = mypk;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getProp() {
            return prop;
        }

        public void setProp(final int prop) {
            this.prop = prop;
        }

        public String getMypk() {
            return mypk;
        }

        public void setMypk(String mypk) {
            this.mypk = mypk;
        }

        public String getConstantProp() {
            return constantProp;
        }
    }
}

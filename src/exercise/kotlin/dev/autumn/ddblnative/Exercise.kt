package dev.autumn.ddblnative

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.describeTable
import aws.sdk.kotlin.services.dynamodb.getItem
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.BillingMode
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType
import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import aws.sdk.kotlin.services.dynamodb.putItem
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
	val (endpoint) = args

	val dynamo = DynamoDbClient {
		region = "us-east-1"
		credentialsProvider = StaticCredentialsProvider {
			accessKeyId = "fakeKeyId"
			secretAccessKey = "fakeSecretAccessKey"
		}

		endpointUrl = Url.parse(endpoint)
	}

	while (true) {
		try {
			dynamo.listTables()
			break
		}
		catch (_: Exception) {
			delay(250)
		}
	}

	dynamo.createTable {
		tableName = "test-table"
		billingMode = BillingMode.PayPerRequest

		attributeDefinitions = listOf(
			AttributeDefinition {
				attributeName = "id"
				attributeType = ScalarAttributeType.S
			}
		)
		keySchema = listOf(
			KeySchemaElement {
				attributeName = "id"
				keyType = KeyType.Hash
			}
		)
	}

	while (true) {
		val result = dynamo.describeTable {
			tableName = "test-table"
		}

		if (result.table?.tableStatus == TableStatus.Active) {
			break
		}

		delay(250)
	}

	dynamo.putItem {
		tableName = "test-table"
		item = mapOf(
			"id" to AttributeValue.S("1"),
			"name" to AttributeValue.S("test")
		)
	}

	dynamo.getItem {
		tableName = "test-table"
		key = mapOf(
			"id" to AttributeValue.S("1")
		)
	}

	exitProcess(0)
}
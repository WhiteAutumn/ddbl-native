package dev.autumn.ddblnative

import com.amazonaws.services.dynamodbv2.local.main.CommandLineInput
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer
import com.amazonaws.services.dynamodbv2.local.server.LocalDynamoDBRequestHandler
import com.amazonaws.services.dynamodbv2.local.server.LocalDynamoDBServerHandler
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

fun main(args: Array<String>) {
	println("Starting DynamoDB Local using extended entry point")

	val extraOptions = Options().apply {
		addOption(
			Option.builder("port")
				.hasArg()
				.argName("0|1-65535")
				.desc("Specify a port number or 0 for an OS-assigned port")
				.build()
		)

		addOption(
			Option.builder("callback")
				.hasArg()
				.argName("url")
				.desc("HTTP endpoint that receives the chosen port")
				.build()
		)
	}

	val extras = DefaultParser().parse(extraOptions, args, true)
	val wantsAutoPort = extras.hasOption("port") && extras.getOptionValue("port") == "0"
	val callbackUrl = extras.getOptionValue("callback")

	if (wantsAutoPort && callbackUrl == null) {
		System.err.println("--port 0 requires --callback <url>")
		exitProcess(1)
	}

	val forwardedArgs = buildList {
		var skip = false
		for (arg in args) {
			if (skip) {
				skip = false
				continue
			}

			when (arg) {
				"--port", "-port" -> {
					if (wantsAutoPort) {
						skip = true
					}
					else {
						add(arg)
					}
				}

				"--callback", "-callback" -> {
					skip = true
				}

				else -> {
					add(arg)
				}
			}
		}
	}.toTypedArray()

	val cli = CommandLineInput(forwardedArgs)
	if (!cli.init()) {
		return
	}

	val server = DynamoDBProxyServer(
		if (wantsAutoPort) 0 else cli.port,
		LocalDynamoDBServerHandler(
			LocalDynamoDBRequestHandler(
				0,
				cli.isInMemory,
				cli.dbPath,
				cli.sharedDb,
				cli.shouldDelayTransientStatuses()
			), cli.corsParams
		)
	)

	server.start()

	if (callbackUrl != null) {
		println("Callback URL: $callbackUrl")

		val field = DynamoDBProxyServer::class.java
			.getDeclaredField("server")

		field.isAccessible = true

		val jettyServer = field.get(server) as org.eclipse.jetty.server.Server
		val port = jettyServer.uri.port

		try {
			val client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build()

			val request = HttpRequest.newBuilder()
				.uri(URI(callbackUrl))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""{"port": $port}"""))
				.timeout(Duration.ofSeconds(10))
				.build()

			val response = client.send(request, HttpResponse.BodyHandlers.ofString())

			if (response.statusCode() !in 200..299) {
				System.err.println("Callback failed: HTTP ${response.statusCode()}")
			}
			else {
				println("Callback successful")
			}
		}
		catch (exception: Exception) {
			System.err.println("Callback failed: ${exception.message}")
		}
	}

	server.join()
}
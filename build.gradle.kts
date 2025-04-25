import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URLClassLoader
import java.util.jar.JarFile

plugins {
	kotlin("jvm") version "2.1.10"
	id("org.graalvm.buildtools.native") version "0.10.6"
}

buildscript {
	dependencies {
		classpath("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
	}
}


group = "dev.autumn"
version = "0.0.1"

val dynamoDbVersion = "2.6.0"

val main: String =
	(findProperty("main") as String?)
		?: "dev.autumn.ddblnative.MainKt"

repositories {
	mavenCentral()
}

dependencies {
	implementation("com.amazonaws:DynamoDBLocal:$dynamoDbVersion") {
		exclude(group = "org.apache.logging.log4j", module = "log4j-core")
		exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
		exclude(group = "org.slf4j", module = "slf4j-api")
	}

	implementation("org.slf4j:slf4j-api:1.7.36")
	implementation("org.apache.logging.log4j:log4j-to-slf4j:2.17.2")
	implementation("commons-cli:commons-cli:1.6.0")

	//runtimeOnly("org.slf4j:slf4j-nop:1.7.36")
	runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
}

kotlin {
	jvmToolchain(21)
}

graalvmNative {
	binaries {
		named("main") {
			imageName.set("${project.name}-$dynamoDbVersion-${project.version}")
			mainClass.set(main)

			configurationFileDirectories.from(layout.buildDirectory.dir("native/config"))

			debug.set(false)
			verbose.set(false)
			fallback.set(false)
		}
	}

	toolchainDetection.set(true)
}

tasks.register("generateReflectConfig") {
	val outFile = layout.buildDirectory.file("native/config/reflect-config.json")
	outputs.file(outFile)

	doLast {
		val json = Json { prettyPrint = true }

		val reflectConfig = buildJsonArray {
			addJsonObject {
				put("name", "org.apache.logging.slf4j.SLF4JLoggerContextFactory")
				putJsonArray("methods") {
					addJsonObject {
						put("name", "<init>")
						putJsonArray("parameterTypes") {}
					}
				}
			}

			addJsonObject {
				put("name", "org.apache.logging.log4j.message.DefaultFlowMessageFactory")
				putJsonArray("methods") {
					addJsonObject {
						put("name", "<init>")
						putJsonArray("parameterTypes") {}
					}
				}
			}

			addJsonObject {
				put("name", "org.apache.logging.log4j.message.ParameterizedMessageFactory")
				putJsonArray("methods") {
					addJsonObject {
						put("name", "<init>")
						putJsonArray("parameterTypes") {}
					}
				}
			}

			val urls = sourceSets.main.get().runtimeClasspath.map { it.toURI().toURL() }
			val loader = URLClassLoader.newInstance(urls.toTypedArray())

			val awsRequest = loader.loadClass("com.amazonaws.AmazonWebServiceRequest")
			val awsResult  = loader.loadClass("com.amazonaws.AmazonWebServiceResult")

			val modelClasses: List<String> =
				sourceSets.main.get().runtimeClasspath
					.filter { it.name.endsWith(".jar") }
					.flatMap { jarFile ->
						JarFile(jarFile).use { jf ->
							jf.entries().asSequence()
								.map { it.name }
								.filter {
									it.startsWith("com/amazonaws/services/dynamodbv2/model/")
										&& it.endsWith(".class")
								}
								.map { it.removeSuffix(".class").replace('/', '.') }
								.toList()
						}
					}
					.distinct()
					.sorted()

			for (name in modelClasses) {
				val cls = runCatching { loader.loadClass(name) }.getOrNull()
					?: continue

				if (awsRequest.isAssignableFrom(cls) || awsResult.isAssignableFrom(cls)) {
					addJsonObject {
						put("name", name)
						put("allDeclaredConstructors", true)
						put("allDeclaredMethods", true)
						put("allDeclaredFields", true)
					}
				}
			}
		}

		outFile.get().asFile.apply {
			parentFile.mkdirs()
			writeText(json.encodeToString(reflectConfig))
		}
	}
}

tasks.named("nativeCompile") {
	dependsOn("generateReflectConfig")
}

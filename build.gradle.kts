import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.ServerSocket
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
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
version = "0.0.3"

val dynamoDbVersion = "2.6.0"

val mainClassName: String =
	(findProperty("main") as String?)
		?: "dev.autumn.ddblnative.MainKt"

val machineArch: String =
	(findProperty("machineArch") as String?)
		?: "native"

sourceSets {
	test {
		kotlin.srcDirs(emptyList<String>())
		resources.srcDirs(emptyList<String>())
	}

	create("exercise") {
		kotlin.srcDir("src/exercise/kotlin")
		resources.srcDir("src/exercise/resources")
	}
}

kotlin {
	jvmToolchain(21)
}

repositories {
	mavenCentral()
}

dependencies {
	val exerciseImplementation by configurations.getting
	val exerciseRuntimeOnly by configurations.getting

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

	exerciseImplementation("aws.sdk.kotlin:dynamodb:1.4.81")
	exerciseRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

graalvmNative {
	binaries {
		named("main") {
			imageName.set("${project.name}-$dynamoDbVersion-${project.version}")
			mainClass.set(mainClassName)

			configurationFileDirectories.from(layout.buildDirectory.dir("native/config"))

			debug.set(false)
			verbose.set(false)
			fallback.set(false)

			buildArgs.add("-march=$machineArch")
		}
	}

	toolchainDetection.set(true)
}

tasks.register("generateReflectConfig") {
	dependsOn("classes", "exerciseClasses")

	val outFile = layout.buildDirectory.file("native/config/reflect-config.json")
	outputs.file(outFile)

	val agentResultDir = project.layout.buildDirectory.dir("native/agent-result").get().asFile
	agentResultDir.deleteRecursively()
	agentResultDir.mkdirs()

	doLast {
		val json = Json { prettyPrint = true }

		val reflectConfig = buildJsonArray {
			val reflectionClasses = mutableSetOf<String>()

			val urls = sourceSets.main.get().runtimeClasspath.map { it.toURI().toURL() }
			val loader = URLClassLoader.newInstance(urls.toTypedArray())

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
				addJsonObject {
					reflectionClasses += name
					put("name", name)
					put("allDeclaredConstructors", true)
					put("allDeclaredMethods", true)
					put("allDeclaredFields", true)
				}
			}

			val objectMapperClass = loader.loadClass(
				"com.amazonaws.services.dynamodbv2.local.shared.mapper.DynamoDBObjectMapper"
			)

			val objectMapperMixinClasses = objectMapperClass.declaredClasses
				.filter { it.simpleName.endsWith("MixIn") }
				.map { it.name }
				.distinct()
				.sorted()

			for (name in objectMapperMixinClasses) {
				addJsonObject {
					reflectionClasses += name
					put("name", name)
					put("allDeclaredConstructors", true)
					put("allDeclaredMethods", true)
					put("allDeclaredFields", true)
				}
			}

			val main by sourceSets.getting
			val exercise by sourceSets.getting

			val port = ServerSocket(0).use { it.localPort }

			val runtimeClassPath = main.runtimeClasspath
				.joinToString(File.pathSeparator) { it.absolutePath }

			val serverProcess = ProcessBuilder(
				"java",
				"-agentlib:native-image-agent=config-output-dir=$agentResultDir",
				"-cp", runtimeClassPath,
				mainClassName,
				"-inMemory", "-sharedDb", "-port", port.toString()
			)
				.start()

			project.javaexec {
				classpath = exercise.runtimeClasspath
				mainClass.set("dev.autumn.ddblnative.ExerciseKt")
				args("http://127.0.0.1:$port")
			}

			serverProcess.destroy()
			serverProcess.waitFor()


			val agentResultText = String(Files.readAllBytes(Paths.get("$agentResultDir/reflect-config.json")))
			for (element in json.parseToJsonElement(agentResultText).jsonArray) {
				val name = element.jsonObject["name"]?.jsonPrimitive?.content
					?: continue

				if (name !in reflectionClasses) {
					reflectionClasses += name
					add(element)
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

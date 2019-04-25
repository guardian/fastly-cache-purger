organization := "com.gu"
description := "Lambda for purging Fastly cache based on Crier events"
scalaVersion := "2.12.8"
name := "fastly-cache-purger"
scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked")

val Log4jVersion = "2.10.0"

libraryDependencies ++= Seq(
  "com.amazonaws" % "amazon-kinesis-client" % "1.9.1",
  "com.amazonaws" % "aws-lambda-java-core" % "1.2.0",
  "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.339",
  "com.squareup.okhttp3" % "okhttp" % "3.2.0",
  "com.gu" %% "content-api-models-scala" % "14.2",
  "com.gu" %% "thrift-serializer" % "4.0.0",
  "org.apache.logging.log4j" % "log4j-api" % Log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % Log4jVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
  "net.logstash.logback" % "logstash-logback-encoder" % "4.11",
  "org.scalatest" %% "scalatest" % "3.0.0" % "test"
)

enablePlugins(RiffRaffArtifact, JavaAppPackaging)

topLevelDirectory in Universal := None
packageName in Universal := normalizedName.value

riffRaffPackageType := (packageBin in Universal).value
riffRaffUploadArtifactBucket := Option("riffraff-artifact")
riffRaffUploadManifestBucket := Option("riffraff-builds")
riffRaffManifestProjectName := s"Content Platforms::${name.value}"

organization := "com.gu"
description := "Lambda for purging Fastly cache based on Crier events"
scalaVersion := "2.12.10"
name := "fastly-cache-purger"
scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked")

val awsClientVersion = "1.12.388"
val circeVersion = "0.12.3"
val Log4jVersion = "2.17.1"

libraryDependencies ++= Seq(
  "com.amazonaws" % "amazon-kinesis-client" % "1.14.10",
  "com.amazonaws" % "aws-lambda-java-core" % "1.2.0",
  "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
  "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion,
  "com.amazonaws" % "aws-java-sdk-s3" % awsClientVersion,
  "com.amazonaws" % "aws-java-sdk-sns" % awsClientVersion,
  "com.squareup.okhttp3" % "okhttp" % "4.9.2",
  "com.gu" %% "content-api-models-scala" % "17.1.1",
  "com.gu" %% "thrift-serializer" % "5.0.2",
  "org.apache.logging.log4j" % "log4j-api" % Log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % Log4jVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
  "net.logstash.logback" % "logstash-logback-encoder" % "5.0",
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "org.scalatest" %% "scalatest" % "3.0.0" % "test"
)

enablePlugins(RiffRaffArtifact, JavaAppPackaging)

Universal / topLevelDirectory := None
Universal / packageName := normalizedName.value

riffRaffPackageType := (Universal / packageBin).value
riffRaffUploadArtifactBucket := Option("riffraff-artifact")
riffRaffUploadManifestBucket := Option("riffraff-builds")
riffRaffManifestProjectName := s"Content Platforms::${name.value}"

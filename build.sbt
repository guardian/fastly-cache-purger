organization := "com.gu"
description := "Lambda for purging Fastly cache based on Crier events"
scalaVersion := "2.12.10"
name := "fastly-cache-purger"
scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked")

val awsClientVersion = "1.12.429"
val circeVersion = "0.14.5"
val Log4jVersion = "2.20.0"

libraryDependencies ++= Seq(
  "com.amazonaws" % "amazon-kinesis-client" % "1.14.10",
  "com.amazonaws" % "aws-lambda-java-core" % "1.2.2",
  "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
  "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion,
  "com.amazonaws" % "aws-java-sdk-s3" % awsClientVersion,
  "com.amazonaws" % "aws-java-sdk-sns" % awsClientVersion,
  "com.squareup.okhttp3" % "okhttp" % "4.10.0",
  "com.gu" %% "content-api-models-scala" % "17.5.1",
  "com.gu" %% "thrift-serializer" % "5.0.2",
  "org.apache.logging.log4j" % "log4j-api" % Log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % Log4jVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
  "net.logstash.logback" % "logstash-logback-encoder" % "7.3",
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "org.scalatest" %% "scalatest" % "3.0.0" % "test"
)

ThisBuild / assemblyJarName := "fastly-cache-purger.jar"
ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case "module-info.class" => MergeStrategy.discard
  case x =>
    val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
    oldStrategy(x)
}
Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", sys.env.getOrElse("SBT_JUNIT_OUTPUT", "junit"))
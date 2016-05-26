organization := "com.gu"
description := "Lambda for purging Fastly cache based on Crier events"
scalaVersion := "2.11.8"
name := "fastly-cache-purger"
scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked")

libraryDependencies ++= Seq(
  "com.amazonaws" % "amazon-kinesis-client" % "1.6.1",
  "com.amazonaws" % "aws-lambda-java-core" % "1.1.0",
  "com.amazonaws" % "aws-lambda-java-events" % "1.1.0",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.10.72",
  "com.squareup.okhttp3" % "okhttp" % "3.2.0",
  "org.apache.thrift" % "libthrift" % "0.9.1" force(),
  "com.twitter" %% "scrooge-core" % "4.6.0",
  "com.gu" % "content-api-models" % "8.16"
)

scroogeThriftDependencies in Compile := Seq("content-api-models", "story-packages-model-thrift", "content-atom-model-thrift")

scroogeThriftSources in Compile ++= {
  (scroogeUnpackDeps in Compile).value.flatMap { dir => (dir ** "*.thrift").get }
}

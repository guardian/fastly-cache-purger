organization := "com.gu"
description := "Lambda for purging Fastly cache based on Crier events"
scalaVersion := "2.12.7"
name := "fastly-cache-purger"
scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked")

libraryDependencies ++= Seq(
  "com.amazonaws" % "amazon-kinesis-client" % "1.9.1",
  "com.amazonaws" % "aws-lambda-java-core" % "1.2.0",
  "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.339",
  "com.squareup.okhttp3" % "okhttp" % "3.2.0",
  "org.apache.thrift" % "libthrift" % "0.9.1" force(),
  "com.twitter" %% "scrooge-core" % "4.18.0",
  "com.gu" %% "content-api-models" % "12.1"
)

enablePlugins(RiffRaffArtifact, JavaAppPackaging)

scroogeThriftDependencies in Compile := Seq("content-api-models_2.12", "story-packages-model-thrift", "content-atom-model-thrift", "content-entity-thrift", "story-model-thrift")

scroogeThriftSources in Compile ++= {
  (scroogeUnpackDeps in Compile).value.flatMap { dir => (dir ** "*.thrift").get }
}

topLevelDirectory in Universal := None
packageName in Universal := normalizedName.value

riffRaffPackageType := (packageBin in Universal).value
riffRaffUploadArtifactBucket := Option("riffraff-artifact")
riffRaffUploadManifestBucket := Option("riffraff-builds")
riffRaffManifestProjectName := s"Content Platforms::${name.value}"

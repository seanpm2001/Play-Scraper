name := """simple"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala, org.nlogo.Scraper)

scalaVersion := "2.11.1"

#!/bin/bash
# ================================================================
# FIX #1: Install kiteconnect 3.5.1 into local Maven repository
# Run this ONCE before building the project.
# ================================================================

echo "Downloading kiteconnect 3.5.1..."
curl -L -o kiteconnect-3.5.1.jar \
  "https://github.com/zerodha/javakiteconnect/raw/master/dist/kiteconnect-3.5.1.jar"

if [ ! -f "kiteconnect-3.5.1.jar" ]; then
  echo "ERROR: Download failed. Please download manually from:"
  echo "https://github.com/zerodha/javakiteconnect/tree/master/dist"
  exit 1
fi

echo "Installing into local Maven repository..."
mvn install:install-file \
  -Dfile=kiteconnect-3.5.1.jar \
  -DgroupId=com.zerodhatech.kiteconnect \
  -DartifactId=kiteconnect \
  -Dversion=3.5.1 \
  -Dpackaging=jar \
  -DgeneratePom=true

echo ""
echo "Done! Now run: mvn spring-boot:run"

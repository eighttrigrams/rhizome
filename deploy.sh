#!/bin/bash

npm i
npx shadow-cljs release app
# clj -M -m uberdeps.uberjar --target server.jar
clj -T:build jar

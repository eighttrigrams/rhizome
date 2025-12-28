#!/bin/bash

npm i
npx shadow-cljs release app
cp server.jar server.jar.bkp
clj -T:build jar

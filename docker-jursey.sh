#!/bin/bash

cd datomic
./bin/transactor config/samples/free-transactor-template.properties &
sleep 4
cd -
lein repl

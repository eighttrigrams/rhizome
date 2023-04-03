# Tracker

An issue tracking system.

## Getting started

```bash
$ npm i
$ cp config.edn.template config.edn # Edit!
$1 ./dev.sh                  # Server
$2 npx shadow-cljs watch app # Frontend
```

Visit `localhost:8020`

## REPL Workflow (Server)

Instead of starting the server with `./dev.sh`, begin with
firing up a REPL, either by jacking-in or by running `clj -M:dev`. 
Then execute the following:

```clojure
clj:user:> (start)
{:started ["#'resources/resources" "#'server/http-server"]}
```

### VSCode

- Jack-in
    - deps.edn
        - Profile: :dev
- Jack-in
    - shadow-cljs
        - :app
            - :app

## Package and run

```bash
$ ./deploy.sh
$ ./start.sh
visit localhost:3000
```

## Clean

```bash
$ rm -rf resources/public/js/*
```

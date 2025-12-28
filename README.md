# Rhizome

For the whitepaper, see here: [*Rhizome - A "total recall" note-taking and content-management and -archival system for Superhuman Memory [Whitepaper]*](https://eighttrigrams.substack.com/p/superhuman-memory)

## Getting started

```bash
$ ln -s </absolute-path-to-your-git-workspace>/tracker/files/Pictures/Tracked resources/public/imgs
$ npm i
$ cp config.edn.template config.edn # Edit! Make sure that :folders :homefolder points to /<.../your-git-workspace>/tracker/files/
$1 ./dev.sh                  # Server
$2 npx shadow-cljs watch app # Frontend
```

Visit `localhost:8020`

## Tests

```clojure
$ clj -X:test
```

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

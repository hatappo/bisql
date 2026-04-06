#!/usr/bin/env bb

(require '[babashka.process :refer [shell]])

(shell {:inherit true}
       "sh" "-lc"
       "docker exec -i bisql-postgres psql -U bisql -d bisql_dev -f /dev/stdin < docker/postgres/init/001_sample_schema.sql")

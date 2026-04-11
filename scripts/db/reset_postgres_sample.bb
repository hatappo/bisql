#!/usr/bin/env bb

(require '[babashka.process :refer [shell]])

(shell {:inherit true}
       "sh" "-lc"
       "{
          printf 'SET client_min_messages TO warning;\\n';
          cat docker/postgres/init/001_sample_schema.sql;
        } | docker exec -i bisql-postgres psql -q -v ON_ERROR_STOP=1 -U bisql -d bisql_dev -f /dev/stdin")

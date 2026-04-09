SELECT status, count(*)
FROM users
GROUP BY status
HAVING
/*%if min-count */
  count(*) >= /*$min-count*/1
/*%end */

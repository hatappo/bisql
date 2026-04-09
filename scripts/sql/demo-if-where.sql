SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%end */

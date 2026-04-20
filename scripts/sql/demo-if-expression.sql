SELECT *
FROM users
WHERE
/*%if (active and status = expected_status) or pending */
  status = 'pending'
/*%else */
  status = 'inactive'
/*%end */

SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%else => status = 'inactive' */
/*%end */

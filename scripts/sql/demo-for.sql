UPDATE users
SET
/*%for item in items separating , */
  /*!item.name*/ = /*$item.value*/'sample'
/*%end */
WHERE id = /*$id*/1

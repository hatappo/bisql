UPDATE users
SET
/*%for item in items separating , */
  /*!item.name*/display_name = /*$item.value*/'sample'
/*%end */
WHERE id = /*$id*/1

CREATE FOREIGN TABLE example (
  price float,
  weight float,
  popularity integer, 
  name string PRIMARy KEY, 
  purchasedate timestamp,
  field string OPTIONS (NAMEINSOURCE 'nis') 
);


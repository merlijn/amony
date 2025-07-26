CREATE TABLE collections (
  id           SERIAL NOT NULL,
  user_id      VARCHAR(64) NOT NULL,
  label        VARCHAR(64) NOT NULL,
  description  VARCHAR,
  CONSTRAINT collections_pk PRIMARY KEY (id),
  CONSTRAINT label_unq UNIQUE (label)
);
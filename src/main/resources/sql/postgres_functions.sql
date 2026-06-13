set search_path=pg_catalog;
CREATE OR REPLACE FUNCTION UNIX_TIMESTAMP() RETURNS BIGINT AS $BODY$ SELECT date_part('epoch',CURRENT_TIMESTAMP)::bigint $BODY$ LANGUAGE 'sql' IMMUTABLE;


set search_path=pg_catalog;CREATE OR REPLACE FUNCTION UNIX_TIMESTAMP(timestamp) RETURNS double precision AS $BODY$ SELECT EXTRACT(EPOCH FROM $1); $BODY$ LANGUAGE sql;


set search_path=pg_catalog;CREATE OR REPLACE FUNCTION FROM_UNIXTIME(double precision,text) RETURNS text AS $BODY$ SELECT to_char(to_timestamp($1),'DD-MM-YYYY HH24:MI:SS'); $BODY$ LANGUAGE sql;

set search_path=pg_catalog;CREATE OR REPLACE FUNCTION ARRAY_TO_CS_STRING(ANYCOMPATIBLE) RETURNS text AS $$ select array_to_string($1,',');  $$ LANGUAGE sql;

set search_path=pg_catalog;CREATE AGGREGATE GROUP_CONCAT(ANYCOMPATIBLE) (SFUNC=array_append,STYPE=anycompatiblearray,INITCOND='{}',FINALFUNC=array_to_cs_string);

set search_path=pg_catalog;CREATE OR REPLACE FUNCTION concat(variadic str text[]) RETURNS text AS $$ SELECT array_to_string($1, ''); $$ LANGUAGE sql;

create schema dbpgextensiondb;
GRANT USAGE ON SCHEMA dbpgextensiondb to public;
set search_path=dbpgextensiondb;CREATE EXTENSION "pgcrypto";

set search_path=pg_catalog;CREATE EXTENSION "citext";
#!/bin/bash
#/*******************************************************************************
# * Copyright 2019 Rundfunk und Telekom Regulierungs-GmbH (RTR-GmbH)
# * 
# * Licensed under the Apache License, Version 2.0 (the "License");
# * you may not use this file except in compliance with the License.
# * You may obtain a copy of the License at
# * 
# *   http://www.apache.org/licenses/LICENSE-2.0
# * 
# * Unless required by applicable law or agreed to in writing, software
# * distributed under the License is distributed on an "AS IS" BASIS,
# * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# * See the License for the specific language governing permissions and
# * limitations under the License.
# ******************************************************************************/

set -x
export LANG=C

# Import Austrian 100m x 100m raster

# Open data site:
# https://www.data.gv.at/katalog/dataset/stat_regionalstatistische-rastereinheiten66c96
# current release
URL=http://data.statistik.gv.at/data/OGDEXT_RASTER_1_STATISTIK_AUSTRIA_L000100_LAEA.zip


mkdir ~/open
cd ~/open

rm STATISTIK_AUSTRIA_L000100_LAEA*

wget $URL
unzip *.zip
rm *.zip


# import as table atraster (takes some time)
# -I create geo index
# -s data is in "ETRS_1989_LAEA" - this is EPSG:3035 (ETRS89-extended / LAEA Europe) => code 3035
# -d drop table if it exists
shp2pgsql -I -s 3035 -d STATISTIK_AUSTRIA_L000100_LAEA.shp atraster | psql rmbt > /dev/null 

# typical record
# INSERT INTO "atraster" ("id","name",geom) VALUES ('100mN27473E45458','CRS3035RES100mN2747300E4545800','01060000000100000001030000000100000005000000000000004257514100000000D2F5444100000000425751410000000004F64441000000005B5751410000000004F64441000000005B57514100000000D2F54441000000004257514100000000D2F54441');
# column name is redundant, can be dropped to save space



# create index on id

sql=$(cat <<EOF
BEGIN;
CREATE INDEX atraster_id_idx
  ON atraster(id);
ANALYSE atraster;
ALTER TABLE atraster OWNER TO rmbt;
GRANT SELECT ON TABLE atraster TO rmbt_group_read_only;
COMMIT;
VACUUM atraster;
EOF
)
echo -e $sql|psql rmbt


# Example query to select a raster based on WGS84 coordinates
# SELECT * from atraster where SELECT * from atraster where ST_intersects((ST_Transform(ST_SetSRID(ST_MakePoint(12.6939,47.074531),4326),3035)),geom);


echo done



# [[file:../org/GridFire.org::raster2pgsql-import-weather-rasters][raster2pgsql-import-weather-rasters]]
#!/bin/sh

USERNAME=$1
SCHEMA=$2
SRID=$3
TILING=$4

echo $4

for LAYER in tmpf wd ws rh
do
    if [ -z "$4" ]
    then
        raster2pgsql -I -C -t auto -s $SRID ${LAYER}_to_sample.tif $SCHEMA.$LAYER | psql -h localhost -U $USERNAME
    else
        raster2pgsql -I -C -t $TILING -s $SRID ${LAYER}_to_sample.tif $SCHEMA.$LAYER | psql -h localhost -U $USERNAME

    fi
done
# raster2pgsql-import-weather-rasters ends here

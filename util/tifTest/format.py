import numpy as np
from osgeo import gdal, osr
import sys, getopt, os
from PIL import Image


def analyze_file(file):
    ds = gdal.Open(file)
    arr = np.array(ds.GetRasterBand(1).ReadAsArray())
    print("Number of bands:", ds.RasterCount)
    out_str = np.array2string(arr, threshold=10000000000)
    out_str = out_str.replace("\n", '')
    out_str = out_str.replace("] [", "\n")
    out_str = out_str.replace("[[", "")
    out_str = out_str.replace("]]", "")
    with open("analyzed.txt", "w") as out_file:
        print(out_str, file=out_file)
        out_file.close()
    max_val = np.max(arr[np.nonzero(arr)])
    min_val = np.min(arr[np.nonzero(arr)])
    print("array size:", arr.shape)
    print(max_val)
    print(min_val)
    normalized = arr
    if (max_val - min_val > 0):
        normalized[np.nonzero(arr)] = (arr[np.nonzero(arr)] - min_val)/(max_val - min_val)*255
    else:
        normalized[np.nonzero(arr)] = 255
    # print(np.array2string(normalized, threshold=np.inf))
    im = Image.fromarray(normalized)
    if (im.mode != "RGB"):
        im = im.convert("RGB")
    im.save("analyzed.jpeg")

def crop_array(arr, newsize, start):
    ret = np.zeros(newsize)
    ret = arr[start[0]:(start[0]+newsize[0]), start[1]:(start[1]+newsize[1])]
    return ret


def crop(file, newsize):
    ds = gdal.Open(file)
    band = ds.GetRasterBand(1)
    arr = np.array(band.ReadAsArray())
    width  = ds.RasterXSize
    height = ds.RasterYSize
    gt = ds.GetGeoTransform()
    # minx = gt[0]
    # miny = gt[3] + width*gt[4] + height*gt[5]
    # maxx = gt[0] + width*gt[1] + height*gt[2]
    # maxy = gt[3]

    mid_pix_x = width//2
    mid_pix_y = height//2
    min_pix_x = mid_pix_x - newsize[1]//2
    min_pix_y = mid_pix_y - newsize[0]//2
    new_arr = crop_array(arr, newsize, (min_pix_y, min_pix_x))

    # print(minx, miny, maxx, maxy)

    # new_arr = crop_array(arr, newsize)
    new_minx = gt[0] + min_pix_x * gt[1] + min_pix_y * gt[2]
    new_maxy = gt[3] + min_pix_x * gt[4] + min_pix_y * gt[5]
    
    new_gt = (new_minx, gt[1], gt[2], new_maxy, gt[4], gt[5])

    output_raster = gdal.GetDriverByName('GTiff').Create('cropped.tif', newsize[1], newsize[0], 1, band.DataType)
    output_raster.SetGeoTransform(new_gt)
    old_cs = osr.SpatialReference()
    old_cs.ImportFromWkt(ds.GetProjectionRef())
    output_raster.SetProjection(old_cs.ExportToWkt())
    output_raster.GetRasterBand(1).WriteArray(new_arr)
    output_raster.FlushCache()

def scale_up_array_interp(arr, factor):
    old_shape = np.shape(arr)
    new_arr = np.zeros((old_shape[0] * factor, old_shape[1] * factor))
    new_shape = np.shape(new_arr)

    for i in range(old_shape[0]):
        for j in range(old_shape[1]):
            new_arr[factor//2 + factor * i, factor//2 + factor * j] = arr[i, j]

    for i in range(old_shape[0]):
        for j in range(new_shape[1]):
            if (j < factor//2):
                new_arr[factor//2 + factor * i, j] = arr[i, 0]
            elif (j > factor//2 + factor * (old_shape[1]-1)):
                new_arr[factor//2 + factor * i, j] = arr[i, old_shape[1]-1]
            elif ((j - factor//2) % factor != 0):
                last = (j - factor//2)//factor
                next = last + 1
                diff = arr[i, next] - arr[i, last]
                interp = (j - factor//2)%factor
                new_arr[factor//2 + factor * i, j] = arr[i, last] + diff/factor*interp
    
    for j in range(new_shape[1]):
        for i in range(new_shape[0]):
            if (i < factor//2):
                new_arr[i, j] = new_arr[factor//2, j]
            elif (i > factor//2 + factor * (old_shape[0]-1)):
                new_arr[i, j] = new_arr[factor//2 + factor * (old_shape[0]-1), j]
            elif((i - factor//2) % factor != 0):
                last = (i - factor//2)//factor
                next = last + 1
                diff = new_arr[factor//2 + next * factor, j] - new_arr[factor//2 + last * factor, j]
                interp = (i - factor//2)%factor
                new_arr[i, j] = new_arr[factor//2 + last * factor, j] + diff/factor*interp
    return new_arr

def scale_up_array(arr, factor):
    old_shape = np.shape(arr)
    new_arr = np.zeros((old_shape[0] * factor, old_shape[1] * factor))
    for i in range(old_shape[0]):
        for j in range(old_shape[1]):
            for k in range(factor):
                for l in range(factor):
                    new_arr[i * factor + k, j * factor + l] = arr[i, j]
    return new_arr


def scale_up(file, factor, interp):
    ds = gdal.Open(file)
    band = ds.GetRasterBand(1)
    arr = np.array(band.ReadAsArray())
    width = ds.RasterXSize
    height = ds.RasterYSize
    if (interp): new_arr = scale_up_array_interp(arr, factor)
    else: new_arr = scale_up_array(arr, factor)
    gt = ds.GetGeoTransform()
    minx = gt[0]
    miny = gt[3] + width*gt[4] + height*gt[5]
    maxx = gt[0] + width*gt[1] + height*gt[2]
    maxy = gt[3]

    new_width = width * factor
    new_height = height * factor
    xres = (maxx - minx)/float(new_width)
    yres = (maxy - miny)/float(new_height)
    new_gt = (minx, xres, 0, maxy, 0, -yres)

    output_raster = gdal.GetDriverByName('GTiff').Create('scaled.tif', width * factor, height * factor, 1, band.DataType)
    output_raster.SetGeoTransform(new_gt)
    old_cs = osr.SpatialReference()
    old_cs.ImportFromWkt(ds.GetProjectionRef())
    output_raster.SetProjection(old_cs.ExportToWkt())
    output_raster.GetRasterBand(1).WriteArray(new_arr)
    output_raster.FlushCache()

def fill_with(file, val):
    ds = gdal.Open(file)
    band = ds.GetRasterBand(1)
    arr = np.array(band.ReadAsArray())
    width = ds.RasterXSize
    height = ds.RasterYSize
    new_arr = np.zeros(np.shape(arr))
    new_arr.fill(val)
    output_raster = gdal.GetDriverByName('GTiff').Create('filled.tif', width, height, 1, band.DataType)
    output_raster.SetGeoTransform(ds.GetGeoTransform())
    old_cs = osr.SpatialReference()
    old_cs.ImportFromWkt(ds.GetProjectionRef())
    output_raster.SetProjection(old_cs.ExportToWkt())
    output_raster.GetRasterBand(1).WriteArray(new_arr)
    output_raster.FlushCache()

def process_one(file, interp):
    scale_up(file, 6, interp)
    crop("scaled.tif", (512, 512))

def process_all():
    for filename in os.listdir('input'):
        if filename.endswith('.tif'):
            full_name = os.path.join('input', filename)
            process_one(full_name, False)
            os.remove("scaled.tif")
            os.rename("cropped.tif", os.path.join('output', filename))

def main(argv):
    file = ''
    analyze = False
    interp = False
    try:
        opts, args = getopt.getopt(argv, "a:i:", ["analyze=", "interp="])
    except getopt.GetoptError:
        print ('format.py -a <filename>')
    for opt, arg in opts:
        if opt in ("-a", "--analyze"):
            file  = arg
            analyze = True
        elif opt in ("-i", "--interp"):
            file = arg
            interp = True
    if (file != ''):
        if (analyze):
            analyze_file(file)
        elif(interp):
            process_one(file, True)
            os.remove("scaled.tif")
            os.rename("cropped.tif", "process_interped.tif")
    else:
        process_all()

if __name__ == "__main__":
    main(sys.argv[1:])
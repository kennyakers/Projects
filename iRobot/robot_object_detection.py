import numpy as np
import os
import six.moves.urllib as urllib
import sys
import tarfile
import tensorflow as tf
import zipfile
import time
import socket

from collections import defaultdict
from io import StringIO
from matplotlib import pyplot as plt
from PIL import Image

sys.path.append("..")
from object_detection.utils import ops as utils_ops

from utils import label_map_util
from utils import visualization_utils as vis_util

import cv2
# pipeline = "nvcamerasrc ! video/x-raw(memory:NVMM), width=(int)1920, height=(int)1080, format=(string)I420, framerate=(fraction)60/1 ! nvvidconv flip-method=2 ! video/x-raw, format=(string)I420 ! videoconvert ! video/x-raw, format=(string)BGR ! appsink"
cap = cv2.VideoCapture(0)

MODEL_NAME = 'ssdlite_mobilenet_v2_coco_2018_05_09'
MODEL_FILE = MODEL_NAME + '.tar.gz'
DOWNLOAD_BASE = 'http://download.tensorflow.org/models/object_detection/'
PATH_TO_CKPT = MODEL_NAME + '/frozen_inference_graph.pb'
PATH_TO_LABELS = os.path.join('data', 'mscoco_label_map.pbtxt')

NUM_CLASSES = 90

VISUALIZE = True
SCORE_THRESHOLD = 0.6

global COUNT
COUNT = 0

HOST = ''                 # Symbolic name meaning all available interfaces
PORT = 12345              # Arbitrary non-privileged port
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind((HOST, PORT))
s.listen(1)
global conn
conn, addr = s.accept()
s.setblocking(0)
print('Connected by', addr)

opener = urllib.request.URLopener()
opener.retrieve(DOWNLOAD_BASE + MODEL_FILE, MODEL_FILE)
tar_file = tarfile.open(MODEL_FILE)
for file in tar_file.getmembers():
    file_name = os.path.basename(file.name)
    if 'frozen_inference_graph.pb' in file_name:
        tar_file.extract(file, os.getcwd())


detection_graph = tf.Graph()

with detection_graph.as_default():
    od_graph_def = tf.GraphDef()
    with tf.gfile.GFile(PATH_TO_CKPT, 'rb') as fid:
        serialized_graph = fid.read()
        od_graph_def.ParseFromString(serialized_graph)
        tf.import_graph_def(od_graph_def, name='')

label_map = label_map_util.load_labelmap(PATH_TO_LABELS)
categories = label_map_util.convert_label_map_to_categories(
    label_map, max_num_classes=NUM_CLASSES, use_display_name=True)
category_index = label_map_util.create_category_index(categories)


def load_image_into_numpy_array(image):
    (im_width, im_height) = image.size
    return np.array(image.getdata()).reshape(
        (im_height, im_width, 3)).astype(np.uint8)


def process(x):

    global COUNT
    if (scores[0][COUNT] > SCORE_THRESHOLD):

        # First val: Top left corner: How far down from top
        # Second val: Top left corner: How far from the left
        # Third val: Bottom right corner: How far down from the top
        # Fourth val: Bottom right corner: How far from the left
        '''
        print("\nItem #", COUNT + 1, ": ", category_index.get(
            int(classes[0][COUNT]), 'name')['name'], sep='')
        print("Score:", "{:.0%}".format(scores[0][COUNT]))
        print(((x[1] + x[3]) / 2), ",", ((x[0] + x[2]) / 2),
              sep='', flush=True)  # (x,y) of center in csv format

        Score: str(scores[0][COUNT])
        y cord: str(((x[0] + x[2]) / 2))
        '''
        # Unix timestamp,Item ID,Classification,X,Y
        to_print = str(time.time()) + "," + str(COUNT) + "," + category_index.get(int(classes[0][COUNT]), 'name')[
            'name'] + "," + str(((x[1] + x[3]) / 2))

        conn.sendall(to_print.encode('utf-8'))
        if (VISUALIZE):
            # Draw box + label
            vis_util.visualize_boxes_and_labels_on_image_array(
                image_np,
                np.squeeze(boxes),
                np.squeeze(classes).astype(np.int32),
                np.squeeze(scores),
                category_index,
                use_normalized_coordinates=True,
                line_thickness=8,
                min_score_thresh=SCORE_THRESHOLD)

            # Draw center
            vis_util.draw_bounding_box_on_image_array(
                image_np,
                ((x[0] + x[2]) / 2) - 0.005,
                ((x[1] + x[3]) / 2) - 0.005,
                ((x[0] + x[2]) / 2) + 0.005,
                ((x[1] + x[3]) / 2) + 0.005,
                color='blue',  # actually draws red
                thickness=8,
                display_str_list=("center"),
                use_normalized_coordinates=True)
    COUNT += 1


with detection_graph.as_default():
    with tf.Session(graph=detection_graph) as sess:
        while True:
            ret, image_np = cap.read()
            # Expand dimensions since the model expects images to have shape: [1, None, None, 3]
            image_np_expanded = np.expand_dims(image_np, axis=0)
            image_tensor = detection_graph.get_tensor_by_name(
                'image_tensor:0')
            # Each box represents a part of the image where a particular object was detected.
            boxes = detection_graph.get_tensor_by_name('detection_boxes:0')
            # Each score represent how level of confidence for each of the objects.
            # Score is shown on the result image, together with the class label.
            scores = detection_graph.get_tensor_by_name(
                'detection_scores:0')

            classes = detection_graph.get_tensor_by_name(
                'detection_classes:0')
            num_detections = detection_graph.get_tensor_by_name(
                'num_detections:0')
            # Actual detection.
            (boxes, scores, classes, num_detections) = sess.run(
                [boxes, scores, classes, num_detections],
                feed_dict={image_tensor: image_np_expanded})

            squeezed_box = np.squeeze(boxes)

            # Visualization of the results of a detection.
            COUNT = 0
            np.apply_along_axis(process, axis=1, arr=squeezed_box)

            if (VISUALIZE):
                cv2.imshow('object detection',
                           cv2.resize(image_np, (800, 600)))

            if cv2.waitKey(25) & 0xFF == ord('q'):
                cv2.destroyAllWindows()
                close_msg = "Close"
                conn.sendall(close_msg.encode('utf-8'))
                conn.shutdown(1)
                conn.close()
                s.close()
                break

import sys, re
sys.path.insert(0, ".")
import fetch_demo_videos as f
allowed = ["public domain","pd","cc0","cc-by","cc by","cc-by-sa","cc by-sa","attribution"]
qs = ["neck exercise","neck pain exercise","neck stretch","cervical exercise","chin tuck","head retraction","neck rotation","neck flexion",
"yoga neck","YOGAnDHARMA","Yoga Hast Vinyasas","back pain exercise","low back exercise","lumbar exercise","McKenzie exercise","cat cow","Marjaryasana",
"Bhujangasana","cobra pose","bridge exercise","pelvic tilt","spine mobility","spinal mobility","posture exercise","posture correction","scapular retraction",
"shoulder blade squeeze","thoracic extension","thoracic mobility","back extension","prone extension","superman exercise","bird dog exercise",
"physiotherapy exercise","physical therapy back","rehabilitation spine","core stability exercise","plank exercise","child pose yoga","spinal twist",
"seated twist","shoulder roll","shoulder shrug","upper trapezius stretch","scoliosis exercise","Schroth","kyphosis","forward head posture","ergonomics posture"]
seen=set()
for q in qs:
    try: pool=f.file_info(f.search(q, 25))
    except Exception as e: print("ERR",q,e); continue
    for i in pool:
        if i["title"] in seen or not f.license_ok(i["license"],allowed) or i["duration"]<4: continue
        seen.add(i["title"])
        print(f"{q} || {i['title']} || {i['license']} || {i['duration']:.0f}s || {i['description'][:120]}")

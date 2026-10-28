// MIT. Small dynamic bridge to the unmodified VideoLAN libvlc C ABI.
#include "CSpare.h"
#include <dlfcn.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <zlib.h>

typedef struct track { int id; char *name; struct track *next; } track;
struct spare_player {
    void *library, *instance, *player;
    track *audio, *subtitles;
    void *(*new_instance)(int, const char *const *);
    void (*release_instance)(void *);
    void *(*new_player)(void *);
    void (*release_player)(void *);
    void *(*new_media)(void *, const char *);
    void (*media_option)(void *, const char *);
    void (*release_media)(void *);
    void (*set_media)(void *, void *);
    void (*bind)(void *, void *);
    int (*play)(void *);
    void (*stop)(void *);
    int (*state)(void *);
    int64_t (*time)(void *);
    float (*fps)(void *);
    int (*size)(void *, unsigned, unsigned *, unsigned *);
    int (*volume)(void *, int);
    void (*pause)(void *, int);
    track *(*audio_tracks)(void *);
    track *(*subtitle_tracks)(void *);
    void (*release_tracks)(track *);
    int (*set_audio)(void *, int);
    int (*set_subtitle)(void *, int);
};
#define LOAD(field, symbol) do { *(void **)(&p->field) = dlsym(p->library, symbol); if (!p->field) goto fail; } while(0)
spare_player *spare_player_create(const char *runtime) {
    spare_player *p = calloc(1, sizeof(*p));
    if (!p) return NULL;
    char path[4096];
    snprintf(path, sizeof(path), "%s/plugins", runtime);
    setenv("VLC_PLUGIN_PATH", path, 1);
    snprintf(path, sizeof(path), "%s/lib/libvlc.5.dylib", runtime);
    p->library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!p->library) goto fail;
    LOAD(new_instance, "libvlc_new"); LOAD(release_instance, "libvlc_release");
    LOAD(new_player, "libvlc_media_player_new"); LOAD(release_player, "libvlc_media_player_release");
    LOAD(new_media, "libvlc_media_new_location"); LOAD(media_option, "libvlc_media_add_option");
    LOAD(release_media, "libvlc_media_release"); LOAD(set_media, "libvlc_media_player_set_media");
    LOAD(bind, "libvlc_media_player_set_nsobject"); LOAD(play, "libvlc_media_player_play");
    LOAD(stop, "libvlc_media_player_stop"); LOAD(state, "libvlc_media_player_get_state");
    LOAD(time, "libvlc_media_player_get_time"); LOAD(fps, "libvlc_media_player_get_fps");
    LOAD(size, "libvlc_video_get_size"); LOAD(volume, "libvlc_audio_set_volume");
    LOAD(pause, "libvlc_media_player_set_pause"); LOAD(audio_tracks, "libvlc_audio_get_track_description");
    LOAD(subtitle_tracks, "libvlc_video_get_spu_description"); LOAD(release_tracks, "libvlc_track_description_list_release");
    LOAD(set_audio, "libvlc_audio_set_track"); LOAD(set_subtitle, "libvlc_video_set_spu");
    const char *args[] = {"--quiet", "--no-video-title-show", "--no-osd", "--no-metadata-network-access", "--no-lua", "--no-stats", "--vout=macosx", "--network-caching=2500", "--file-caching=1000", "--avcodec-hw=any", "--http-user-agent=Spare-Mac"};
    p->instance = p->new_instance(sizeof(args)/sizeof(args[0]), args);
    if (!p->instance) goto fail;
    p->player = p->new_player(p->instance);
    if (!p->player) goto fail;
    return p;
fail:
    if (p->instance && p->release_instance) p->release_instance(p->instance);
    // Keep the loaded library resident: VLC may still have shutdown workers.
    free(p); return NULL;
}
void spare_player_bind(spare_player *p, void *view) { if (p) p->bind(p->player, view); }
int spare_player_play(spare_player *p, const char *url) {
    if (!p) return -1;
    void *media = p->new_media(p->instance, url);
    if (!media) return -1;
    p->media_option(media, ":network-caching=2500");
    p->set_media(p->player, media); p->release_media(media);
    if (p->audio) { p->release_tracks(p->audio); p->audio = NULL; }
    if (p->subtitles) { p->release_tracks(p->subtitles); p->subtitles = NULL; }
    return p->play(p->player);
}
void spare_player_stop(spare_player *p) { if (p) p->stop(p->player); }
void spare_player_destroy(spare_player *p) { if (!p) return; p->stop(p->player); p->release_player(p->player); if (p->audio) p->release_tracks(p->audio); if (p->subtitles) p->release_tracks(p->subtitles); p->release_instance(p->instance); free(p); }
int spare_player_state(spare_player *p) { return p ? p->state(p->player) : 7; }
int64_t spare_player_time(spare_player *p) { return p ? p->time(p->player) : -1; }
float spare_player_fps(spare_player *p) { return p ? p->fps(p->player) : 0; }
unsigned spare_player_width(spare_player *p) { unsigned w=0,h=0; if (p) p->size(p->player,0,&w,&h); return w; }
unsigned spare_player_height(spare_player *p) { unsigned w=0,h=0; if (p) p->size(p->player,0,&w,&h); return h; }
void spare_player_volume(spare_player *p, int v) { if (p) p->volume(p->player,v); }
void spare_player_pause(spare_player *p, int v) { if (p) p->pause(p->player,v); }
int spare_player_track_count(spare_player *p, int sub) {
    if (!p) return 0;
    track **list = sub ? &p->subtitles : &p->audio;
    if (*list) p->release_tracks(*list);
    *list = sub ? p->subtitle_tracks(p->player) : p->audio_tracks(p->player);
    int n=0; for (track *t=*list; t && n<128; t=t->next) n++;
    return n;
}
static track *at(spare_player *p, int sub, int index) { if (!p || index<0) return NULL; track *t=sub?p->subtitles:p->audio; while (t && index--) t=t->next; return t; }
int spare_player_track_id(spare_player *p, int sub, int i) { track *t=at(p,sub,i); return t?t->id:-1; }
const char *spare_player_track_name(spare_player *p, int sub, int i) { track *t=at(p,sub,i); return t&&t->name?t->name:"Unknown"; }
void spare_player_track_select(spare_player *p, int sub, int id) { if (p) { if(sub) p->set_subtitle(p->player,id); else p->set_audio(p->player,id); } }
size_t spare_gunzip(const void *input, size_t size, void *output, size_t capacity) {
    z_stream s={0}; s.next_in=(Bytef *)input; s.avail_in=(uInt)size; s.next_out=output; s.avail_out=(uInt)capacity;
    if (inflateInit2(&s,15+32)!=Z_OK) return 0;
    int result=inflate(&s,Z_FINISH); size_t count=s.total_out; inflateEnd(&s);
    return result==Z_STREAM_END?count:0;
}

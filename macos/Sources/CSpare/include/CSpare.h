#ifndef CSPARE_H
#define CSPARE_H
#include <stdint.h>
#include <stddef.h>
typedef struct spare_player spare_player;
spare_player *spare_player_create(const char *runtime);
void spare_player_bind(spare_player *, void *view);
int spare_player_play(spare_player *, const char *url);
void spare_player_stop(spare_player *);
void spare_player_destroy(spare_player *);
int spare_player_state(spare_player *);
int64_t spare_player_time(spare_player *);
float spare_player_fps(spare_player *);
unsigned spare_player_width(spare_player *);
unsigned spare_player_height(spare_player *);
void spare_player_volume(spare_player *, int volume);
void spare_player_pause(spare_player *, int paused);
int spare_player_track_count(spare_player *, int subtitles);
int spare_player_track_id(spare_player *, int subtitles, int index);
const char *spare_player_track_name(spare_player *, int subtitles, int index);
void spare_player_track_select(spare_player *, int subtitles, int id);
size_t spare_gunzip(const void *input, size_t size, void *output, size_t capacity);
#endif

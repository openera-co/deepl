// Mirrors whisperLive in the app: chunks of at most 10 s, greedy, reduced audio_ctx.
#include "whisper.h"
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>
#include <fstream>
#include <algorithm>
static std::vector<float> wav(const char* p){std::ifstream f(p,std::ios::binary);std::vector<char> b((std::istreambuf_iterator<char>(f)),{});
 size_t o=12; uint16_t fmt=1; std::vector<float> out; while(o+8<=b.size()){std::string id(b.data()+o,4); uint32_t n; memcpy(&n,b.data()+o+4,4);
 if(id=="fmt "){memcpy(&fmt,b.data()+o+8,2);} if(id=="data"){ if(fmt==3){auto*s=(float*)(b.data()+o+8); for(size_t i=0;i<n/4;i++) out.push_back(s[i]);} else {auto*s=(int16_t*)(b.data()+o+8); for(size_t i=0;i<n/2;i++) out.push_back(s[i]/32768.f);} break;} o+=8+n;} return out;}
int main(int argc,char**argv){ // model mode(0=full ctx,1=reduced) files...
 auto cp=whisper_context_default_params(); cp.use_gpu=false; cp.flash_attn=true;
 auto*ctx=whisper_init_from_file_with_params(argv[1],cp); int mode=atoi(argv[2]); double tot=0, audio=0;
 for(int a=3;a<argc;a++){ auto pcm=wav(argv[a]); std::string text, prompt;
  for(size_t st=0; st<pcm.size(); st+=160000){ size_t n=std::min<size_t>(160000,pcm.size()-st);
   auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY); p.language="fr"; p.n_threads=4; p.print_progress=false;p.print_realtime=false;p.print_timestamps=false;
   p.no_timestamps=true;p.no_context=true;p.suppress_blank=true;p.temperature_inc=(mode&2)?0.2f:0.f; if(mode&4) p.max_tokens=(int)(n/16000.0*7)+8;
   if(mode&1) p.audio_ctx=std::min(1500,(int)(n/16000.0*50.0)+128);
   std::string pr=prompt.empty()?"Bonjour à tous. Aujourd'hui, nous étudions le chapitre suivant.":prompt; p.initial_prompt=pr.c_str();
   auto t0=std::chrono::steady_clock::now(); whisper_full(ctx,p,pcm.data()+st,(int)n);
   tot+=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count(); audio+=n/16000.0;
   std::string c; for(int i=0;i<whisper_full_n_segments(ctx);i++) c+=whisper_full_get_segment_text(ctx,i);
   text+=c; prompt=c.size()>180?c.substr(c.size()-180):c; }
  const char*b=strrchr(argv[a],'/'); printf("%s\t%s\n", b?b+1:argv[a], text.c_str()); fflush(stdout);}
 fprintf(stderr,"compute %.1fs for %.1fs audio (%.2fx realtime)\n",tot,audio,tot/audio);}

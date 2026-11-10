// Translates pre-tokenised sentences (one per line, space-separated pieces) with CTranslate2 built the
// way the app builds it (Ruy, no MKL), and prints the speed. usage: ct2_cli <modelDir> <threads> <in> <out>
#include <ctranslate2/translator.h>
#include <chrono>
#include <fstream>
#include <iostream>
#include <sstream>

int main(int argc, char** argv) {
  ctranslate2::ReplicaPoolConfig pool; pool.num_threads_per_replica = std::stoi(argv[2]); pool.max_queued_batches = 0;
  auto t0 = std::chrono::steady_clock::now();
  ctranslate2::Translator tr(argv[1], ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8, {0}, false, pool);
  double load = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  std::ifstream in(argv[3]); std::vector<std::vector<std::string>> src; std::string line;
  while (std::getline(in, line)) { std::istringstream ss(line); std::vector<std::string> t; std::string w; while (ss >> w) t.push_back(w); src.push_back(t); }
  ctranslate2::TranslationOptions opt; opt.beam_size = 1; opt.max_decoding_length = 256;
  tr.translate_batch({src[0]}, opt);
  auto t1 = std::chrono::steady_clock::now();
  std::ofstream out(argv[4]);
  for (size_t i = 0; i < src.size(); i += 16) {
    std::vector<std::vector<std::string>> b(src.begin() + i, src.begin() + std::min(src.size(), i + 16));
    for (auto& r : tr.translate_batch(b, opt)) { for (auto& t : r.output()) out << t << ' '; out << '\n'; }
  }
  double dt = std::chrono::duration<double>(std::chrono::steady_clock::now() - t1).count();
  std::cout << "app-build CT2 " << argv[2] << " threads: " << src.size() / dt << " lines/s, load " << load << "s" << std::endl;
}

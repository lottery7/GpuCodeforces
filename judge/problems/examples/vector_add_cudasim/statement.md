# Сложение векторов (GPGPU-Sim)

То же, что `vector_add`, но на модели GPU NVIDIA QV100 в GPGPU-Sim: `solve` запускает ядра, `A`, `B`, `C` — память
GPU. Score — такты симулятора (`gpu_tot_sim_cycle`) за вызов `solve`: меньше — лучше. Копирования памяти в решении
запрещены, warp-shuffle (`__shfl_*_sync`) тоже: GPGPU-Sim считает их неверно.

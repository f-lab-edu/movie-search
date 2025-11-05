package com.jg.moviesearch.ui.viewmodel

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jg.moviesearch.core.domain.repository.MovieRepository
import com.jg.moviesearch.core.model.domain.MovieWithPoster
import com.jg.moviesearch.ui.model.MovieUiEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MovieViewModel @Inject constructor(
    private val movieRepository: MovieRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MovieUiState())
    val uiState: StateFlow<MovieUiState> = _uiState.asStateFlow()


    private val _uiEffect = MutableSharedFlow<MovieUiEffect>()
    val uiEffect: SharedFlow<MovieUiEffect> = _uiEffect.asSharedFlow()

    // ==================== 초기화 ====================
    init {
        setupRealTimeSearch()
        observeFavoriteMovies()
    }

    // ==================== 검색 관련 함수들 ====================

    // 실시간 검색 설정
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun setupRealTimeSearch() {
        _uiState
            .map { it.searchQuery }
            .distinctUntilChanged()
            .debounce(300)
            .filter { it.trim().isNotEmpty() }
            .onEach { query ->
                resetSearchState()
                showLoading()
            }
            //  새로운 query가 들어오면 이전의 이 블록 실행은 자동으로 취소
            .flatMapLatest { query ->
                movieRepository.searchMoviesWithPoster(movieName = query.trim(), page = 1)
                    .catch {
                        handleError(
                            message = it.message ?: "Unknown error",
                            prefix = "영화 검색 실패"
                        )
                        emit(emptyList())
                    }
            }
            .onEach { movie ->
                if (movie.isEmpty()) {
                    handleSearchEmpty()
                } else {
                    handleSearchSuccess(movies = movie)
                }
            }.launchIn(viewModelScope)
    }

    // 검색어 업데이트
    fun updateSearchQuery(query: String) {
        _uiState.update {
            it.copy(searchQuery = query)
        }

        if (query.trim().isEmpty()) {
            clearSearchResults()
        }
    }

    // ==================== 무한 스크롤 관련 함수들 ====================

    // 무한 스크롤 조건 체크
    fun shouldLoadMore(lastVisibleItemIndex: Int): Boolean {
        val currentState = _uiState.value
        return lastVisibleItemIndex >= currentState.movies.size - 3 &&
                currentState.hasMoreData &&
                !currentState.isLoadingMore &&
                currentState.movies.size >= 10
    }

    // 추가 데이터 로드 (무한 스크롤)
    fun loadMoreMovies() {
        val currentState = _uiState.value

        if (currentState.isLoadingMore || !currentState.hasMoreData || currentState.searchQuery.isEmpty()) {
            return
        }

        _uiState.update { state ->
            state.copy(isLoadingMore = true)
        }

        movieRepository.searchMoviesWithPoster(currentState.searchQuery, currentState.currentPage + 1)
            .catch { e ->
                handleError(
                    message = e.message ?: "Unknown error",
                    prefix = "추가 데이터 로드 실패",
                    isLoadMore = true
                )
            }
            .onEach { newMovies ->
                if (newMovies.isEmpty()) {
                    handleLoadMoreEmpty()
                } else {
                    handleLoadMoreSuccess(
                        newMovies = newMovies,
                        currentPage = currentState.currentPage
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    // ==================== 즐겨찾기 관련 함수들 ====================

    // 즐겨찾기 상태 관찰
    private fun observeFavoriteMovies() {
        movieRepository.getAllFavoriteMovies()
            .onEach { favoriteMovies ->
                val favoriteMovieCds = favoriteMovies.map { it.movie.movieCd }.toSet()
                _uiState.update { state ->
                    state.copy(favoriteMovies = favoriteMovieCds)
                }
            }
            .launchIn(viewModelScope)
    }

    // 즐겨찾기
    fun toggleFavorite(movieWithPoster: MovieWithPoster) {
        viewModelScope.launch {
            runCatching {
                val isFavorite =
                    _uiState.value.favoriteMovies.contains(movieWithPoster.movie.movieCd)

                if (isFavorite) {
                    movieRepository.removeFavoriteMovie(movieCd = movieWithPoster.movie.movieCd)
                } else {
                    movieRepository.addFavoriteMovie(movie = movieWithPoster)
                }
            }.onFailure {
                handleError(message = it.message ?: "Unknown error", prefix = "즐겨찾기 처리 중 오류")
            }
        }
    }

    // ==================== UI 상태 관리 ====================

    // 로딩 상태 표시
    private fun showLoading() {
        _uiState.update { state ->
            state.copy(isLoading = true)
        }
    }

    // 공통 에러 처리 함수
    private fun handleError(message: String, prefix: String, isLoadMore: Boolean = false) {
        _uiState.update { state ->
            state.copy(
                isLoading = if (!isLoadMore) false else state.isLoading,
                isLoadingMore = if (isLoadMore) false else state.isLoadingMore
            )
        }

        viewModelScope.launch {
            _uiEffect.emit(MovieUiEffect.ShowError("$prefix: $message"))
        }
    }

    // 검색 결과 초기화
    private fun clearSearchResults() {
        _uiState.update {
            it.copy(
                movies = emptyList(),
                processedMovies = emptyList(),
                isLoading = false
            )
        }
    }

    // 검색 상태 초기화
    private fun resetSearchState() {
        _uiState.update {
            it.copy(
                currentPage = 1,
                hasMoreData = true,
                movies = emptyList(),
                processedMovies = emptyList()
            )
        }
    }

    // 검색 영화 클릭
    fun onMovieClick(movies: List<MovieWithPoster>, movie: MovieWithPoster) {
        viewModelScope.launch {
            _uiEffect.emit(MovieUiEffect.NavigateToDetail(movies, movies.indexOf(movie)))
        }
    }

    // ==================== 검색 결과 처리 함수들 ====================

    // 검색 성공 처리
    private fun handleSearchSuccess(movies: List<MovieWithPoster>) {
        val processedMovies = processMoviesForDisplay(movies = movies)

        _uiState.update { state ->
            state.copy(
                movies = movies,
                processedMovies = processedMovies,
                isLoading = false,
                hasMoreData = movies.size >= 10
            )
        }
    }

    // 검색 결과 없음 처리
    private fun handleSearchEmpty() {
        _uiState.update { state ->
            state.copy(
                movies = emptyList(),
                processedMovies = emptyList(),
                isLoading = false,
                hasMoreData = false
            )
        }
        viewModelScope.launch {
            _uiEffect.emit(MovieUiEffect.ShowError("검색 결과가 없습니다"))
        }
    }

    // ==================== 무한 스크롤 결과 처리 함수들 ====================

    // 무한 스크롤 성공 처리
    private fun handleLoadMoreSuccess(newMovies: List<MovieWithPoster>, currentPage: Int) {
        val currentMovies = _uiState.value.movies
        val allMovies = currentMovies + newMovies
        val processedMovies = processMoviesForDisplay(movies = allMovies)

        _uiState.update { state ->
            state.copy(
                movies = allMovies,
                processedMovies = processedMovies,
                isLoadingMore = false,
                currentPage = currentPage + 1,
                hasMoreData = newMovies.isNotEmpty() && newMovies.size >= 10
            )
        }
    }

    // 무한 스크롤 결과 없음 처리
    private fun handleLoadMoreEmpty() {
        _uiState.update { state ->
            state.copy(
                isLoadingMore = false,
                hasMoreData = false
            )
        }
    }

    // ==================== 유틸리티 함수들 ====================

    // 영화 데이터를 MovieDisplayItem으로 변환
    private fun processMoviesForDisplay(movies: List<MovieWithPoster>): List<MovieDisplayItem> {
        return movies.mapIndexed { index, movieWithPoster ->
            MovieDisplayItem(
                movieWithPoster = movieWithPoster,
                displayType = MovieDisplayType.fromIndex(index)
            )
        }
    }
}

data class MovieUiState(
    val searchQuery: String = "",
    val movies: List<MovieWithPoster> = emptyList(),
    val processedMovies: List<MovieDisplayItem> = emptyList(), // 추가
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val favoriteMovies: Set<String> = emptySet(),
    val hasMoreData: Boolean = true,
    val currentPage: Int = 1
)

data class MovieDisplayItem(
    val movieWithPoster: MovieWithPoster,
    val displayType: MovieDisplayType
)

sealed class MovieDisplayType {
    object Poster : MovieDisplayType()
    object Text : MovieDisplayType()

    companion object {
        fun fromIndex(index: Int): MovieDisplayType {
            return if ((index % 6) < 3) Poster else Text
        }
    }
}

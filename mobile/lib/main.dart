import 'package:flutter/material.dart';

import 'api.dart';
import 'screens/home_screen.dart';
import 'screens/playlists_screen.dart';

void main() {
  runApp(const LibraryApp());
}

const _accent = Color(0xFFFFE0C2); // hsl(29.5 100% 88%) — the warm brand peach
const _onAccent = Color(0xFF081A1B); // hsl(183.2 54.3% 6.9%)
const _bg = Color(0xFF111111);
const _card = Color(0xFF191919);
const _muted = Color(0xFF222222);
const _mutedFg = Color(0xFFB4B4B4);
const _border = Color(0xFF201E18);

class LibraryApp extends StatelessWidget {
  const LibraryApp({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = ColorScheme.fromSeed(seedColor: _accent, brightness: Brightness.dark)
        .copyWith(
      primary: _accent,
      onPrimary: _onAccent,
      surface: _bg,
      onSurface: const Color(0xFFEEEEEE),
      surfaceContainerHighest: _card,
      outline: _border,
    );

    return MaterialApp(
      title: 'Library',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: scheme,
        scaffoldBackgroundColor: _bg,
        cardColor: _card,
        hintColor: _mutedFg,
        appBarTheme: const AppBarTheme(
          backgroundColor: _bg,
          surfaceTintColor: Colors.transparent,
          elevation: 0,
          centerTitle: false,
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: _card,
          hintStyle: TextStyle(fontSize: 13, color: _mutedFg.withValues(alpha: .5)),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: BorderSide.none,
          ),
          contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        ),
        chipTheme: const ChipThemeData(
          backgroundColor: _card,
          selectedColor: _accent,
          side: BorderSide.none,
          shape: StadiumBorder(),
          labelStyle: TextStyle(fontSize: 12.5),
        ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            backgroundColor: _accent,
            foregroundColor: _onAccent,
            padding: const EdgeInsets.symmetric(vertical: 14),
            shape: const StadiumBorder(),
          ),
        ),
        navigationBarTheme: NavigationBarThemeData(
          backgroundColor: _bg,
          indicatorColor: _muted,
          surfaceTintColor: Colors.transparent,
          labelTextStyle: WidgetStatePropertyAll(
            TextStyle(fontSize: 11.5, color: _mutedFg),
          ),
        ),
      ),
      home: Shell(api: Api()),
    );
  }
}

/// Two tabs, one Api instance shared so the session and catalog caches are
/// genuinely shared rather than duplicated per screen.
class Shell extends StatefulWidget {
  const Shell({super.key, required this.api});

  final Api api;

  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _index,
        children: [
          HomeScreen(api: widget.api),
          PlaylistsScreen(api: widget.api),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.grid_view_outlined),
            selectedIcon: Icon(Icons.grid_view),
            label: 'Browse',
          ),
          NavigationDestination(
            icon: Icon(Icons.queue_music_outlined),
            selectedIcon: Icon(Icons.queue_music),
            label: 'Playlists',
          ),
        ],
      ),
    );
  }
}
